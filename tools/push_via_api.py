#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""用 GitHub Git Data API 推送提交。

沙箱只放行 api.github.com，github.com:443 连不上，所以 `git push` 用不了。
这里做的和 push 完全等价：blob → tree → commit → 移动 ref。
"""
import base64
import json
import os
import subprocess
import sys
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
REPO = "dsdzm/DSH-Watch"
BASE = None   # 由远端 head 决定，见 main
HEAD = None   # 本地 HEAD


def token() -> str:
    for line in (ROOT / ".env").read_text(encoding="utf-8").splitlines():
        if line.startswith("GITHUB_TOKEN="):
            return line.split("=", 1)[1].strip()
    raise SystemExit("缺少 GITHUB_TOKEN")


TOKEN = token()


def api(method: str, path: str, payload=None):
    url = f"https://api.github.com{path}"
    data = json.dumps(payload).encode() if payload is not None else None
    req = urllib.request.Request(url, data=data, method=method)
    req.add_header("Authorization", f"Bearer {TOKEN}")
    req.add_header("Accept", "application/vnd.github+json")
    if data:
        req.add_header("Content-Type", "application/json")
    try:
        with urllib.request.urlopen(req, timeout=60) as r:
            return json.loads(r.read() or b"{}")
    except urllib.error.HTTPError as e:
        print("HTTP", e.code, path, file=sys.stderr)
        print(e.read().decode()[:500], file=sys.stderr)
        raise


def git(*args) -> str:
    return subprocess.check_output(["git", *args], cwd=ROOT, text=True).strip()


ref = api("GET", f"/repos/{REPO}/git/ref/heads/main")
base_sha = ref["object"]["sha"]
base_commit = api("GET", f"/repos/{REPO}/git/commits/{base_sha}")
print(f"远端 head = {base_sha[:7]}")

# 远端 head 可能是「用 API 建的、本地没有」的 commit（sha 不同、内容相同），
# 这时 git diff 会报 bad object —— 允许用 PUSH_BASE 指定一个内容等价的本地 commit。
BASE = os.environ.get("PUSH_BASE") or base_sha
HEAD = git("rev-parse", "HEAD")

changed = []
for line in git("diff", "--name-status", BASE, HEAD).splitlines():
    status, path = line.split("\t", 1)
    changed.append((status, path))

tree = []
for status, path in changed:
    if status == "D":
        tree.append({"path": path, "mode": "100644", "type": "blob", "sha": None})
        continue
    raw = (ROOT / path).read_bytes()
    blob = api("POST", f"/repos/{REPO}/git/blobs",
               {"content": base64.b64encode(raw).decode(), "encoding": "base64"})
    mode = "100755" if os.access(ROOT / path, os.X_OK) else "100644"
    tree.append({"path": path, "mode": mode, "type": "blob", "sha": blob["sha"]})
    print(f"  blob {status} {path} -> {blob['sha'][:7]}")

new_tree = api("POST", f"/repos/{REPO}/git/trees",
               {"base_tree": base_commit["tree"]["sha"], "tree": tree})
message = git("log", "-1", "--pretty=%B", HEAD)
commit = api("POST", f"/repos/{REPO}/git/commits",
             {"message": message, "tree": new_tree["sha"], "parents": [base_sha]})
api("PATCH", f"/repos/{REPO}/git/refs/heads/main",
    {"sha": commit["sha"], "force": False})

print(f"提交完成 {commit['sha'][:7]}")
print(f"https://github.com/{REPO}/commit/{commit['sha']}")
