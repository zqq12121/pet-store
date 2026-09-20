#!/usr/bin/env python3
"""生成仅本机保存的 Compose 配置；已有文件不覆盖，密钥不输出。"""
import base64
import os
from pathlib import Path
import secrets
import shlex

ROOT = Path(__file__).resolve().parents[1]


def read_env(path):
    values = {}
    if path.exists():
        for line in path.read_text().splitlines():
            line = line.strip().removeprefix("export ")
            if not line or line.startswith("#") or "=" not in line:
                continue
            key, value = line.split("=", 1)
            parts = shlex.split(value, comments=True)
            values[key.strip()] = parts[0] if parts else ""
    return values


def main():
    target = ROOT / ".env"
    if target.exists():
        raise SystemExit("根目录 .env 已存在，未覆盖。请在本机编辑该文件。")
    values = read_env(ROOT / ".env.example")
    sources = {**read_env(ROOT / "backend/.env"), **read_env(ROOT / "ai-service/.env"), **os.environ}
    for key in values:
        if sources.get(key):
            values[key] = sources[key]
    for key in ("MYSQL_ROOT_PASSWORD", "PAW_DB_PASSWORD", "PAW_REDIS_PASSWORD", "PAW_ADMIN_PASSWORD", "NACOS_AUTH_IDENTITY_VALUE"):
        if not values.get(key):
            values[key] = secrets.token_urlsafe(32)
    values["NACOS_AUTH_TOKEN"] = base64.b64encode(secrets.token_bytes(48)).decode()
    # 单引号防止 Compose 对密码中的美元符号进行变量展开。
    with os.fdopen(os.open(target, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600), "w") as output:
        for key, value in values.items():
            if "\n" in value or "\r" in value:
                raise ValueError(f"{key} 必须是单行配置")
            output.write(key + "='" + value.replace("'", "\\'") + "'\n")
    print("已生成根目录 .env（权限 600），未输出任何密钥。")
    for key in ("DEEPSEEK_API_KEY", "PAW_SMS_SIGN_NAME", "PAW_SMS_CONFIRM_TEMPLATE", "PAW_SMS_APPOINTMENT_SUBMITTED_TEMPLATE", "PAW_SMS_APPOINTMENT_CONFIRMED_TEMPLATE", "PAW_SMS_APPOINTMENT_CANCELLED_TEMPLATE", "PAW_SMS_APPOINTMENT_EXPIRED_TEMPLATE", "PAW_SMS_APPOINTMENT_COMPLETED_TEMPLATE"):
        print(key + (": 已配置" if values.get(key) else ": 待配置"))


if __name__ == "__main__":
    main()
