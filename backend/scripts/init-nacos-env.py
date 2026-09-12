#!/usr/bin/env python3
"""生成 Nacos 3 镜像启动所需的本机随机身份配置；不覆盖已有值，不打印密钥。"""
import base64
import os
from pathlib import Path
import secrets

path = Path(__file__).resolve().parents[1] / '.nacos.env'
if not path.exists():
    values = {
        'NACOS_AUTH_TOKEN': base64.b64encode(secrets.token_bytes(48)).decode(),
        'NACOS_AUTH_IDENTITY_KEY': 'warmpawLocal',
        'NACOS_AUTH_IDENTITY_VALUE': secrets.token_hex(32),
    }
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(fd, 'w') as output:
        output.write(''.join(f'{key}={value}\n' for key, value in values.items()))
