#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
把新版本发到 GitHub Releases，供 App 的「检查更新」读取。

客户端只做一件事：`GET /repos/dsdzm/DSH-Watch/releases/latest`，然后从
资产列表里找 `dshwatch-<versionCode>.apk`。所以发版必须遵守两条约定：

  1. tag 是 `v<versionName>`，例如 `v1.15.19`；
  2. 安装包资产名是 `dshwatch-<versionCode>.apk`。

第二条不能省：GitHub 的 API 不返回 versionCode，客户端只能从文件名里取。
名字写错 ⇒ 手机上永远显示「已是最新」，而且没有任何报错。

用法
----
    python3 tools/publish_update.py                 # 默认 APK + 自动取更新日志
    python3 tools/publish_update.py --notes "修复 xxx"
    python3 tools/publish_update.py --dry-run       # 只打印将要发生什么
    python3 tools/publish_update.py --force         # 同名 release 已存在时删掉重建

凭证放项目根 `.env`：

    GITHUB_TOKEN=ghp_xxx     # 需要 Contents: Read and write
    GITHUB_REPO=dsdzm/DSH-Watch
"""

import argparse
import json
import os
import re
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
GRADLE = ROOT / "app" / "build.gradle.kts"
CHANGELOG = ROOT / "app" / "src" / "main" / "java" / "com" / "dshwatch" / "domain" / "AppChangelog.kt"
DEFAULT_APK = ROOT / "app" / "build" / "outputs" / "apk" / "release" / "app-release.apk"

API = "https://api.github.com"
UPLOAD = "https://uploads.github.com"

APK_MIME = "application/vnd.android.package-archive"


def load_env() -> dict:
    """读 .env；已存在的环境变量优先，方便 CI 里直接注入"""
    env = {}
    f = ROOT / ".env"
    if f.exists():
        for raw in f.read_text(encoding="utf-8").splitlines():
            line = raw.strip()
            if not line or line.startswith("#") or "=" not in line:
                continue
            k, v = line.split("=", 1)
            env[k.strip()] = v.strip().strip('"').strip("'")
    for k, v in os.environ.items():
        if k.startswith("GITHUB_"):
            env[k] = v
    return env


def read_version() -> tuple:
    """从 build.gradle.kts 读版本号。

    不让人手输：覆盖安装要求 versionCode 严格递增、签名一致，
    手抄错一位的后果是「装不上」或者「装上了却是旧包」，很难排查。
    """
    text = GRADLE.read_text(encoding="utf-8")
    vc = re.search(r"versionCode\s*=\s*(\d+)", text)
    vn = re.search(r'versionName\s*=\s*"([^"]+)"', text)
    if not vc or not vn:
        sys.exit("读不到 versionCode / versionName，检查 app/build.gradle.kts")
    return int(vc.group(1)), vn.group(1)


def notes_from_changelog(version: str) -> list:
    """从 AppChangelog 抓当前版本的 highlights，省得每次手写一遍。

    不能用一个「到最近的 `),` 为止」的正则 —— 每条说明后面都跟着逗号，
    非贪婪会在第一条之后就停住，结果是只发布一半的更新说明。
    """
    if not CHANGELOG.exists():
        return []
    text = CHANGELOG.read_text(encoding="utf-8")
    head = re.search(r'version\s*=\s*"' + re.escape(version) + r'"', text)
    if not head:
        return []
    tail = text[head.end(): head.end() + 1500]
    start = re.search(r"highlights\s*=\s*", tail)
    if not start:
        return []
    body = tail[start.end():]
    end = re.search(r"\n\s*\)\s*,", body)
    if end:
        body = body[: end.start()]
    return re.findall(r'"([^"]+)"', body)


def gh(
    method: str,
    path: str,
    token: str,
    body=None,
    content_type: str = "application/json",
    base: str = API,
) -> dict:
    """打一次 GitHub API，失败时把服务端的话原样带出来。

    GitHub 的错误信息其实很有用（`already_exists` / `bad_credentials` /
    速率限制的 reset 时间），包一层反而丢线索。
    """
    data = None
    if body is not None:
        data = body if isinstance(body, bytes) else json.dumps(body, ensure_ascii=False).encode("utf-8")

    req = urllib.request.Request(
        f"{base}{path}",
        data=data,
        method=method,
        headers={
            "Authorization": f"Bearer {token}",
            "Accept": "application/vnd.github+json",
            "X-GitHub-Api-Version": "2022-11-28",
            "User-Agent": "dsh-watch-release",
            "Content-Type": content_type,
        },
    )
    try:
        with urllib.request.urlopen(req, timeout=600) as resp:
            raw = resp.read()
            return json.loads(raw) if raw.strip() else {}
    except urllib.error.HTTPError as e:
        detail = e.read().decode("utf-8", "replace")[:500]
        if e.code == 401:
            sys.exit("GitHub 鉴权失败：GITHUB_TOKEN 无效或已过期")
        if e.code == 403:
            sys.exit(f"GitHub 拒绝：{detail}\n（token 需要 Contents: Read and write 权限）")
        sys.exit(f"{method} {path} → HTTP {e.code}\n{detail}")
    except urllib.error.URLError as e:
        sys.exit(f"连不上 GitHub：{e.reason}\n（国内直连 api.github.com 偶尔会超时，可以重试一次）")


def find_release(repo: str, tag: str, token: str):
    """按 tag 找 release，没有就返回 None（404 是正常分支，不当错误）"""
    req = urllib.request.Request(
        f"{API}/repos/{repo}/releases/tags/{tag}",
        headers={
            "Authorization": f"Bearer {token}",
            "Accept": "application/vnd.github+json",
            "User-Agent": "dsh-watch-release",
        },
    )
    try:
        with urllib.request.urlopen(req, timeout=30) as resp:
            return json.loads(resp.read())
    except urllib.error.HTTPError as e:
        if e.code == 404:
            return None
        raise


def build_body(notes: list) -> str:
    """没有说明就留空 —— 客户端会把正文拆成条目显示，
    塞一句「常规更新。」进去，界面上就多出一条没有信息量的假说明。"""
    if not notes:
        return ""
    return "### 更新内容\n\n" + "\n".join(f"- {n}" for n in notes)


def main() -> int:
    ap = argparse.ArgumentParser(description="发布应用更新到 GitHub Releases")
    ap.add_argument("apk", nargs="?", default=str(DEFAULT_APK), help="APK 路径")
    ap.add_argument("--notes", action="append", default=[], help="更新说明，可重复")
    ap.add_argument("--dry-run", action="store_true", help="只打印计划，不发请求")
    ap.add_argument("--force", action="store_true", help="同名 release 已存在时删除重建")
    args = ap.parse_args()

    apk = Path(args.apk)
    if not apk.is_file():
        sys.exit(f"找不到 APK：{apk}")

    version_code, version_name = read_version()
    notes = args.notes or notes_from_changelog(version_name)
    tag = f"v{version_name}"
    asset_name = f"dshwatch-{version_code}.apk"

    print(f"版本    {version_name} ({version_code})")
    print(f"tag     {tag}")
    print(f"资产    {asset_name}   {apk.stat().st_size / 1024 / 1024:.1f} MB")
    print(f"说明    {len(notes)} 条")
    for n in notes:
        print(f"        · {n}")

    if not re.fullmatch(r"dshwatch-\d+\.apk", asset_name):
        sys.exit("资产名不符合 dshwatch-<versionCode>.apk，客户端会认不出来")

    if args.dry_run:
        print("\n[dry-run] 将执行：")
        print(f"  1. POST /repos/<repo>/releases          创建 tag {tag}")
        print(f"  2. POST uploads.github.com/.../assets   上传 {asset_name}")
        print("  3. GET  /repos/<repo>/releases/latest   回读校验")
        return 0

    env = load_env()
    token = env.get("GITHUB_TOKEN")
    repo = env.get("GITHUB_REPO", "dsdzm/DSH-Watch")
    if not token:
        sys.exit("缺少 GITHUB_TOKEN（写在项目根 .env 里，参考 .env.example）")

    print(f"\n仓库    {repo}")

    existing = find_release(repo, tag, token)
    if existing:
        if not args.force:
            sys.exit(
                f"release {tag} 已经存在。\n"
                f"如果要重发，加 --force（会删掉旧 release 和 tag 后重建）。"
            )
        print(f"  删除旧的 release {existing['id']} …")
        gh("DELETE", f"/repos/{repo}/releases/{existing['id']}", token)
        # 只删 release 不会删 tag，重建同名 tag 会 422
        try:
            gh("DELETE", f"/repos/{repo}/git/refs/tags/{tag}", token)
        except SystemExit:
            pass

    print(f"\n创建 release {tag} …")
    release = gh(
        "POST",
        f"/repos/{repo}/releases",
        token,
        {
            "tag_name": tag,
            "name": version_name,
            "body": build_body(notes),
            "draft": False,
            "prerelease": False,
        },
    )
    release_id = release["id"]

    print(f"上传 {asset_name} …")
    gh(
        "POST",
        f"/repos/{repo}/releases/{release_id}/assets?name={urllib.parse.quote(asset_name)}",
        token,
        apk.read_bytes(),
        content_type=APK_MIME,
        base=UPLOAD,
    )

    print("\n校验 …")
    for i in range(3):
        try:
            latest = gh("GET", f"/repos/{repo}/releases/latest", token)
            names = [a["name"] for a in latest.get("assets", [])]
            if latest.get("tag_name") == tag and asset_name in names:
                digest = next(
                    (a.get("digest") for a in latest["assets"] if a["name"] == asset_name), None
                )
                print(f"完成：https://github.com/{repo}/releases/tag/{tag}")
                print(f"      {asset_name}  digest={digest or '（GitHub 尚未生成）'}")
                return 0
            print(f"  第 {i + 1} 次：latest={latest.get('tag_name')}，资产 {names}")
        except SystemExit:
            raise
        except Exception as e:  # noqa: BLE001
            print(f"  第 {i + 1} 次校验失败：{e}")
        time.sleep(3)

    print("\nrelease 已创建，但 /releases/latest 还没指过来（GitHub 有一点延迟）。")
    print("稍等一分钟再打开 App 检查更新即可。")
    return 1


if __name__ == "__main__":
    raise SystemExit(main())
