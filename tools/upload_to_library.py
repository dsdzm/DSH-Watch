#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""把本地任意文件上传到 WorkBuddy 资料库（drive 文件）。

用法：
    python3 tools/upload_to_library.py <本地文件> [--file-name 展示名] [--space-id ID] [--parent-id ID]

流程：get-upload-credential → PUT 到 COS → import-local-file 落库。
成功输出 KS_UPLOAD_OK {node_block_id,file_name,url}；失败输出 {"error":...} 且退出码 0。
"""

from __future__ import annotations

import argparse
import json
import os
import sys
import urllib.error
import urllib.request
from pathlib import Path

_LIB_DIR = Path("/root/.codebuddy/skills/library")
if str(_LIB_DIR) not in sys.path:
    sys.path.insert(0, str(_LIB_DIR))

from _common import HttpError, error_exit, http_request, safe_print, unwrap_data  # noqa: E402

MAX_BYTES = 200 * 1024 * 1024
PUT_TIMEOUT = 300


def main() -> None:
    parser = argparse.ArgumentParser(add_help=True)
    parser.add_argument("path")
    parser.add_argument("--file-name", dest="file_name", default="")
    parser.add_argument("--space-id", dest="space_id", default="")
    parser.add_argument("--parent-id", dest="parent_id", default="")
    args = parser.parse_args()

    path = os.path.expanduser(args.path.strip())
    if not os.path.isfile(path):
        error_exit("文件不存在")
    size = os.path.getsize(path)
    if size <= 0 or size > MAX_BYTES:
        error_exit("文件为空或超出大小上限")

    file_name = (args.file_name or os.path.basename(path)).strip()
    if not file_name:
        error_exit("文件名为空")

    body: dict = {"fileName": file_name, "fileSize": size}
    if args.parent_id:
        body["parentId"] = args.parent_id
    elif args.space_id:
        body["parentId"] = args.space_id

    try:
        cred = unwrap_data(
            http_request(
                "POST",
                "/space/api/agent/v1/get-upload-credential",
                body=body,
                timeout=15.0,
            )
        )
    except HttpError as e:
        error_exit(f"获取上传凭证失败: {e}")

    upload_url = (cred.get("uploadUrl") or "").strip()
    cos_key = (cred.get("cosKey") or "").strip()
    headers = cred.get("headers") or {}
    if not upload_url:
        error_exit("凭证缺少 uploadUrl")

    with open(path, "rb") as f:
        content = f.read()
    req = urllib.request.Request(upload_url, data=content, method="PUT")
    for k, v in headers.items():
        req.add_header(k, v)
    try:
        with urllib.request.urlopen(req, timeout=PUT_TIMEOUT) as resp:
            if not (200 <= resp.status < 300):
                error_exit(f"上传 COS 失败: HTTP {resp.status}")
    except (urllib.error.HTTPError, urllib.error.URLError, OSError, ValueError) as e:
        error_exit(f"上传 COS 失败: {e}")

    import_body: dict = {
        "fileName": file_name,
        "confirmKey": (cred.get("confirmKey") or "").strip(),
        "taskId": (cred.get("taskId") or "").strip(),
    }
    if cos_key:
        import_body["cosKey"] = cos_key
    if args.space_id:
        import_body["spaceId"] = args.space_id
    if args.parent_id:
        import_body["parentId"] = args.parent_id
    title = os.path.splitext(file_name)[0].strip()
    if title:
        import_body["title"] = title

    try:
        data = unwrap_data(
            http_request(
                "POST",
                "/space/api/agent/v1/import-local-file",
                body=import_body,
                timeout=180.0,
            )
        )
    except HttpError as e:
        error_exit(f"落库失败: {e}")

    node_id = (data.get("nodeBlockId") or "").strip()
    if not node_id:
        error_exit("落库响应缺少 nodeBlockId")
    safe_print(
        "KS_UPLOAD_OK "
        + json.dumps(
            {
                "node_block_id": node_id,
                "file_name": (data.get("fileName") or file_name),
                "url": (data.get("url") or "").strip(),
            },
            ensure_ascii=False,
            separators=(",", ":"),
        )
    )


if __name__ == "__main__":
    main()
