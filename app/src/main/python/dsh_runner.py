"""DSH 本地 Python 执行器。

由 Kotlin 侧经 Chaquopy 调用：`dsh_runner.run(code, work_dir, timeout_sec, max_chars, args_json)`。

返回**一段 JSON 字符串**：跨语言边界只传字符串，比拼装对象少一大类错误。

## 参数为什么用 JSON 传

真机事故：Kotlin 侧把 `ArrayList` 直接传进来，Python 侧 `for a in args` 报
`TypeError: 'ArrayList' object is not iterable` —— Chaquopy 把 Java 集合当**对象**，
它不是 Python 的 list；而且空列表也一样会炸（Python 里任意对象都判真）。
所以参数编成 JSON 字符串传，这里 `json.loads` 解回来。

## 超时为什么不是 `sys.settrace`

最初的实现用 `sys.settrace` 装死线守卫，超时就从 trace 函数里抛异常。
**这条路在 CPython 上不通**：trace 函数里抛出的异常会被解释器吞掉并关闭追踪，
于是 `while True: pass` 会一直跑下去 —— 表现为「超时不生效、脚本永远不返回」。
（本机实测复现过。）

现在改成：用户代码跑在**独立线程**里，主线程只负责等与打断，超时后用
`PyThreadState_SetAsyncExc` 给目标线程扔一个 `BaseException` 派生类。
这是 CPython 认可的中断运行中线程的手段，纯 Python 循环一定能停下。

**仍然拦不住的情况**：阻塞在 C 扩展里的代码（例如某个 numpy 调用内部死循环）。
这时会如实把 `still_running` 标为 true，Kotlin 侧据此保持执行闸门占用。
"""

import contextlib
import io
import json
import os
import sys
import threading
import time
import traceback


class ScriptTimeout(BaseException):
    """超时中断标记。用 BaseException 派生：用户代码的 `except Exception` 不该吞掉它"""


# 原始流：万一某次执行没能收尾，也要把 stdout/stderr 复原，
# 否则后续执行会继续写进那个已经废弃的缓冲区
_ORIG_STDOUT = sys.stdout
_ORIG_STDERR = sys.stderr


def _cap(text, limit):
    """超长输出保留头尾，与 Kotlin 侧同一套规则。"""
    limit = int(limit)
    if limit <= 0:
        return ("", bool(text))
    if len(text) <= limit:
        return (text, False)
    half = max(limit // 2, 1)
    dropped = len(text) - limit
    return (text[:half] + "\n…（省略 %d 个字符）…\n" % dropped + text[-half:], True)


def _interrupt(thread_ident):
    """尽力打断目标线程。返回 True 表示内核接受了请求（不代表线程已退出）"""
    try:
        import ctypes

        result = ctypes.pythonapi.PyThreadState_SetAsyncExc(
            ctypes.c_ulong(thread_ident),
            ctypes.py_object(ScriptTimeout),
        )
        if result > 1:
            # 官方建议：返回值 >1 说明打错了多个线程，需要回滚
            ctypes.pythonapi.PyThreadState_SetAsyncExc(ctypes.c_ulong(thread_ident), None)
            return False
        return result == 1
    except Exception:
        return False


def _dumps(payload):
    try:
        return json.dumps(payload, ensure_ascii=False)
    except Exception:
        # 输出里混进不可序列化的东西时，退化成纯文本，绝不因此让整次执行失败
        return json.dumps(
            {
                "exit": 1,
                "stdout": str(payload.get("stdout", ""))[:2000],
                "stderr": "结果序列化失败（输出中包含无法编码的字符）",
                "timed_out": False,
                "truncated": True,
                "elapsed_ms": 0,
                "still_running": False,
            },
            ensure_ascii=False,
        )


def run(code, work_dir, timeout_sec, max_chars=20000, args_json=""):
    started = time.time()
    out_buf = io.StringIO()
    err_buf = io.StringIO()
    state = {"exit": 0, "timed_out": False, "still_running": False}

    # ---- 参数（JSON，见模块说明）----
    try:
        extra_args = json.loads(args_json) if args_json else []
    except Exception:
        extra_args = []
    if not isinstance(extra_args, list):
        extra_args = []

    old_cwd = os.getcwd()
    old_argv = list(sys.argv)
    old_path = list(sys.path)

    def worker():
        try:
            if work_dir:
                try:
                    os.chdir(work_dir)
                except OSError as e:
                    err_buf.write("无法切换到工作目录 %s：%s\n" % (work_dir, e))
                if work_dir not in sys.path:
                    sys.path.insert(0, work_dir)

            sys.argv = ["<dsh-script>"] + [str(a) for a in extra_args]

            try:
                compiled = compile(code, "<dsh-script>", "exec")
            except SyntaxError as e:
                err_buf.write("语法错误（第 %s 行）：%s\n" % (e.lineno, e.msg))
                state["exit"] = 1
                return

            with contextlib.redirect_stdout(out_buf), contextlib.redirect_stderr(err_buf):
                exec(compiled, {"__name__": "__main__", "__doc__": None})

        except ScriptTimeout:
            # 主线程判定超时后扔进来的；timed_out 已经由主线程置位
            pass
        except SystemExit as e:
            code_value = getattr(e, "code", 0)
            state["exit"] = code_value if isinstance(code_value, int) else 0
        except BaseException:
            # BaseException：KeyboardInterrupt / MemoryError 也要留下痕迹
            err_buf.write(traceback.format_exc())
            state["exit"] = 1
        finally:
            try:
                os.chdir(old_cwd)
            except OSError:
                pass
            sys.argv = old_argv
            sys.path[:] = old_path
            # 复原输出流（万一我们被打断在中途）
            if sys.stdout is not _ORIG_STDOUT:
                sys.stdout = _ORIG_STDOUT
            if sys.stderr is not _ORIG_STDERR:
                sys.stderr = _ORIG_STDERR

    limit = float(timeout_sec or 0)
    script_thread = threading.Thread(target=worker, name="dsh-script", daemon=True)
    script_thread.start()
    script_thread.join(limit if limit > 0 else None)

    if script_thread.is_alive():
        state["timed_out"] = True
        state["exit"] = 1
        _interrupt(script_thread.ident)
        # 给它一点时间走到 finally 收尾（纯 Python 循环通常会立刻停）
        script_thread.join(1.5)
        state["still_running"] = script_thread.is_alive()

    stdout_text, out_trunc = _cap(out_buf.getvalue(), max_chars)
    stderr_text, err_trunc = _cap(err_buf.getvalue(), max_chars)

    if state["timed_out"]:
        suffix = "执行超过 %d 秒，已被中止。" % int(limit)
        if state["still_running"]:
            suffix += "（线程仍在运行：可能阻塞在 C 扩展里，无法强制终止）"
        stderr_text = (stderr_text + "\n" if stderr_text else "") + suffix

    return _dumps(
        {
            "exit": int(state["exit"]),
            "stdout": stdout_text,
            "stderr": stderr_text,
            "timed_out": bool(state["timed_out"]),
            "truncated": bool(out_trunc or err_trunc),
            "elapsed_ms": int((time.time() - started) * 1000),
            "still_running": bool(state["still_running"]),
        }
    )
