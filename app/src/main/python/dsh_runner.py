"""DSH 本地 Python 执行器。

由 Kotlin 侧经 Chaquopy 调用：`dsh_runner.run(code, work_dir, timeout_sec, max_chars, args)`。

返回**一段 JSON 字符串**：跨语言边界只传一个字符串，比拼装对象少一大类错误。

## 关于超时

CPython 与 App 跑在同一个进程里，没有独立解释器可以 kill ——
`Kotlin 侧终止 Python` 这件事在 Chaquopy 上做不到（官方文档明确说明）。

所以这里用 `sys.settrace` 装一个死线守卫：每执行若干条语句检查一次时钟，
超时就抛 `SystemExit` 把控制权夺回来。能拦住纯 Python 的死循环；
**拦不住 C 扩展里的死循环**（例如 numpy 卡在某个函数里），
这一点不粉饰，Kotlin 侧会把 `still_running` 如实告诉用户。
"""

import contextlib
import io
import json
import os
import sys
import time
import traceback

# 超时用一个几乎不可能与用户输出撞车的标记串
TIMEOUT_MARK = "__DSH_TIMEOUT__"

# 不是每条语句都读时钟（读时钟本身也要钱），攒够这么多次再查一次。
# 取值要小到让 `while True: pass` 在一秒内被抓住，又大到不吃掉正常脚本的性能。
CLOCK_CHECK_INTERVAL = 100


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


def run(code, work_dir, timeout_sec, max_chars=20000, args=None):
    started = time.time()
    out_buf = io.StringIO()
    err_buf = io.StringIO()
    state = {"timed_out": False, "exit": 0, "still_running": False}

    timeout_sec = float(timeout_sec or 0)
    deadline = started + timeout_sec if timeout_sec > 0 else None

    # ---- 死线守卫 ----
    ticks = [0]

    def _guard(frame, event, arg):
        ticks[0] += 1
        if deadline is not None and ticks[0] % CLOCK_CHECK_INTERVAL == 0:
            if time.time() > deadline:
                state["timed_out"] = True
                raise SystemExit(TIMEOUT_MARK)
        return _guard

    old_cwd = os.getcwd()
    old_argv = list(sys.argv)
    old_path = list(sys.path)

    try:
        if work_dir:
            try:
                os.chdir(work_dir)
            except OSError as e:
                err_buf.write("无法切换到工作目录 %s：%s\n" % (work_dir, e))
            if work_dir not in sys.path:
                sys.path.insert(0, work_dir)

        sys.argv = ["<dsh-script>"] + [str(a) for a in (args or [])]

        try:
            compiled = compile(code, "<dsh-script>", "exec")
        except SyntaxError as e:
            err_buf.write(
                "语法错误（第 %s 行）：%s\n" % (e.lineno, e.msg)
            )
            state["exit"] = 1
            compiled = None

        if compiled is not None:
            if deadline is not None:
                sys.settrace(_guard)
            try:
                with contextlib.redirect_stdout(out_buf), contextlib.redirect_stderr(err_buf):
                    exec(compiled, {"__name__": "__main__", "__doc__": None})
            finally:
                sys.settrace(None)

    except SystemExit as e:
        if str(e) == TIMEOUT_MARK:
            state["timed_out"] = True
            state["exit"] = 1
        else:
            # 用户脚本自己调了 sys.exit(n)
            code_value = getattr(e, "code", 0)
            state["exit"] = code_value if isinstance(code_value, int) else 0
    except BaseException:
        # 用 BaseException：KeyboardInterrupt / MemoryError 也要留下痕迹
        err_buf.write(traceback.format_exc())
        state["exit"] = 1
    finally:
        sys.settrace(None)
        try:
            os.chdir(old_cwd)
        except OSError:
            pass
        sys.argv = old_argv
        sys.path[:] = old_path

    stdout_text, out_trunc = _cap(out_buf.getvalue(), max_chars)
    stderr_text, err_trunc = _cap(err_buf.getvalue(), max_chars)

    elapsed_ms = int((time.time() - started) * 1000)
    if deadline is not None and state["timed_out"]:
        stderr_text = (stderr_text + "\n" if stderr_text else "") + (
            "执行超过 %d 秒，已被中止。" % int(timeout_sec)
        )

    return _dumps(
        {
            "exit": int(state["exit"]),
            "stdout": stdout_text,
            "stderr": stderr_text,
            "timed_out": bool(state["timed_out"]),
            "truncated": bool(out_trunc or err_trunc),
            "elapsed_ms": elapsed_ms,
            "still_running": bool(state["still_running"]),
        }
    )
