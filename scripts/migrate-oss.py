#!/usr/bin/env python3
"""将原文件迁入指定 OSS 项目前缀；校验 MD5，拒绝覆盖不同内容的已有对象。"""
import base64
import email.utils
import hashlib
import hmac
import json
import os
from pathlib import Path
import re
import urllib.request
import urllib.error
import xml.etree.ElementTree as ET

from importlib.util import spec_from_file_location, module_from_spec

ROOT = Path(__file__).resolve().parents[1]
spec = spec_from_file_location("compose_env", ROOT / "scripts/init-compose-env.py")
env_module = module_from_spec(spec)
spec.loader.exec_module(env_module)


def main():
    values = {**env_module.read_env(ROOT / ".env"), **os.environ}
    bucket = values["PAW_OSS_BUCKET"]
    endpoint = values["PAW_OSS_ENDPOINT"].removeprefix("https://")
    access, secret = values["OSS_ACCESS_KEY_ID"], values["OSS_ACCESS_KEY_SECRET"]
    if not re.fullmatch(r"[a-z0-9-]+", bucket) or not re.fullmatch(r"oss-[a-z0-9-]+\.aliyuncs\.com", endpoint):
        raise SystemExit("Bucket/官方 HTTPS Endpoint 格式不合法")

    def request(method, key, data=None):
        date = email.utils.formatdate(usegmt=True)
        headers = {"Date": date}
        md5 = base64.b64encode(hashlib.md5(data).digest()).decode() if data is not None else ""
        mime = "application/octet-stream" if data is not None else ""
        extra = "x-oss-object-acl:private\n" if data is not None else ""
        signature = f"{method}\n{md5}\n{mime}\n{date}\n{extra}/{bucket}/{key}"
        headers["Authorization"] = "OSS " + access + ":" + base64.b64encode(hmac.new(secret.encode(), signature.encode(), hashlib.sha1).digest()).decode()
        if data is not None:
            headers.update({"Content-MD5": md5, "Content-Type": mime, "x-oss-object-acl": "private"})
        return urllib.request.urlopen(urllib.request.Request(f"https://{bucket}.{endpoint}/{key}", data=data, headers=headers, method=method), timeout=30)

    report = []
    for path in sorted((ROOT / "backend/data/files").iterdir()):
        if not path.is_file() or not re.fullmatch(r"file_[a-f0-9]{32}", path.name):
            continue
        data = path.read_bytes()
        digest = hashlib.md5(data).hexdigest()
        key = "warmpaw/files/" + path.name
        exists = False
        try:
            with request("HEAD", key) as response:
                if response.headers["ETag"].strip('"').lower() != digest:
                    raise SystemExit("发现内容不同的已有对象，停止迁移：" + path.name)
                exists = True
        except urllib.error.HTTPError as error:
            if error.code != 404:
                raise SystemExit(f"OSS 读取失败 HTTP {error.code}，请检查 Bucket 与 RAM 权限") from None
        if not exists:
            with request("PUT", key, data) as response:
                if response.headers["ETag"].strip('"').lower() != digest:
                    raise SystemExit("OSS 上传摘要不一致")
        # 实际 GET 并比对，不能仅以 PUT 受理替代数据校验。
        with request("GET", key) as response:
            if hashlib.sha256(response.read()).digest() != hashlib.sha256(data).digest():
                raise SystemExit("OSS 回读校验失败")
        with request("GET", key + "?acl") as response:
            if ET.fromstring(response.read()).findtext("AccessControlList/Grant") != "private":
                raise SystemExit("OSS 对象不是私有权限，停止切换")
        report.append({"id": path.name, "sha256": hashlib.sha256(data).hexdigest(), "verified": True})
    folder = ROOT / "backups"
    folder.mkdir(exist_ok=True, mode=0o700)
    (folder / "oss-migration.json").write_text(json.dumps(report, indent=2))
    print("OSS 上传并回读校验通过：", len(report), "个文件；源文件保留。")


if __name__ == "__main__":
    main()
