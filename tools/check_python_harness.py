#!/usr/bin/env python3
"""本机验收 `dsh_runner.py`（Python 执行器）。

为什么需要这个脚本：执行器里的关键行为**单元测试覆盖不到** —— 它要在真 Python 里跑。
而它恰好是最容易悄悄坏掉的一环：

- **Kotlin ↔ Python 传参**：曾经把 `ArrayList` 直接传给 Chaquopy，
  Python 侧 `for a in args` 报 `TypeError: 'ArrayList' object is not iterable`，
  连空列表也一样炸 —— 于是 Python 执行 100% 失败。
- **超时机制**：曾经用 `sys.settrace` 装死线守卫、从 trace 函数里抛异常，
  而 CPython 会吞掉 trace 函数里的异常并关闭追踪 —— `while True: pass` 永远不返回，
  表现为「超时没生效、脚本卡死」。

这两类问题都只在真机上暴露。所以改完 `dsh_runner.py` 后，本机跑一遍这个脚本，
比等真机反馈快得多。

用法（不需要 Android / Chaquopy，只用标准库）：

    python3 tools/check_python_harness.py

全部通过时退出码为 0。
"""

import json
import os
import sys
import tempfile
import time

REPO_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, os.path.join(REPO_ROOT, "app", "src", "main", "python"))

import dsh_runner  # noqa: E402  （必须在插入路径之后导入）

FAILURES = []


def call(code, timeout=5, args=None, work=None, max_chars=2000):
    return json.loads(
        dsh_runner.run(code, work, timeout, max_chars, json.dumps(args or []))
    )


def check(name, condition, extra=""):
    print(("  ✓ " if condition else "  ✗ ") + name + ("" if condition else "  → " + str(extra)[:300]))
    if not condition:
        FAILURES.append(name)


def main():
    work = tempfile.mkdtemp(prefix="dsh-harness-")
    print("工作目录:", work)

    print("=== 1. 基本输出 ===")
    r = call("print('hello')\nprint(1+1)", work=work)
    check("stdout 正确、退出码 0", r["stdout"] == "hello\n2\n" and r["exit"] == 0, r)

    print("=== 2. 位置参数（历史故障点）===")
    r = call("import sys; print(sys.argv)", args=["a", "b c"], work=work)
    check("argv 收到参数（含空格）", "'a'" in r["stdout"] and "'b c'" in r["stdout"], r)

    print("=== 3. 空参数也必须能跑（历史故障点）===")
    r = call("print('noargs')", work=work)
    check("空参数不报错", r["exit"] == 0 and "noargs" in r["stdout"], r)

    print("=== 4. 异常留下 traceback ===")
    r = call("1/0", work=work)
    check("退出码 1 且含异常类型", r["exit"] == 1 and "ZeroDivisionError" in r["stderr"], r)

    print("=== 5. 语法错误不崩 ===")
    r = call("def f(:", work=work)
    check("报语法错误", r["exit"] == 1 and "语法错误" in r["stderr"], r)

    print("=== 6. 超时打断纯 Python 死循环（历史故障点）===")
    started = time.time()
    r = call("while True: pass", timeout=2, work=work)
    elapsed = time.time() - started
    check("被标记为超时", r["timed_out"] is True and r["exit"] == 1, r)
    check("线程已停下（不是仍在跑）", r["still_running"] is False, r)
    check("耗时接近 2 秒而不是挂死（实际 %.1fs）" % elapsed, elapsed < 6, elapsed)

    print("=== 7. try/except Exception 吞不掉超时 ===")
    r = call(
        "while True:\n    try:\n        pass\n    except Exception:\n        pass",
        timeout=2,
        work=work,
    )
    check("仍然被中断", r["timed_out"] is True, r)

    print("=== 8. sys.exit(n) 退出码透传 ===")
    r = call("import sys; sys.exit(3)", work=work)
    check("退出码 3", r["exit"] == 3, r)

    print("=== 9. 输出截断保留头尾 ===")
    r = call("for i in range(3000): print('line-%d' % i)", timeout=10, work=work, max_chars=2000)
    check("标记截断", r["truncated"] is True, r)
    check("保留了结尾（报错通常在这里）", "line-2999" in r["stdout"], r["stdout"][-80:])

    print("=== 10. cwd 与文件写入 ===")
    r = call("import os; print(os.getcwd())", work=work)
    check("cwd 是工作目录", work in r["stdout"], r)
    call("open('out.txt','w').write('from-python')", work=work)
    check("脚本能写文件", os.path.exists(os.path.join(work, "out.txt")))

    print("=== 11. 超时后仍能继续执行（流与状态被复原）===")
    call("while True: pass", timeout=1, work=work)
    r = call("print('after-timeout')", work=work)
    check("后续执行正常", r["exit"] == 0 and "after-timeout" in r["stdout"], r)

    print()
    if FAILURES:
        print("失败 %d 项：" % len(FAILURES))
        for name in FAILURES:
            print("  -", name)
        return 1
    print("全部通过")
    return 0


if __name__ == "__main__":
    sys.exit(main())
