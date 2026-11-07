#!/usr/bin/env python3
"""给 --fresh 空库隔离验收补齐最少业务数据：门店营业时间、两份已发布协议、一只带检疫证明的在售示例宠物。

只允许连接本机隔离验收环境（图形认证配置必须是 demo-acceptance-only），全部走后台接口。
用法：python3 scripts/seed-acceptance.py [--base http://127.0.0.1:8190/api/v1]
"""
import argparse
import json
from pathlib import Path
import struct
import urllib.error
import urllib.request
import uuid
import zlib
from typing import Any

ROOT = Path(__file__).resolve().parents[1]
ADMIN_PASSWORD = 'DemoAdmin2026!'  # DemoAdminApplication 在隔离副本里设置的固定测试密码，非真实凭据


class Api:
    def __init__(self, base: str) -> None:
        self.base = base.rstrip('/')
        self.token: str | None = None

    def call(self, method: str, path: str, body: Any = None, raw: bytes | None = None,
             content_type: str | None = None) -> Any:
        headers = {'Idempotency-Key': str(uuid.uuid4())}
        if self.token:
            headers['Authorization'] = 'Bearer ' + self.token
        data = raw
        if body is not None:
            data, content_type = json.dumps(body, ensure_ascii=False).encode(), 'application/json'
        if content_type:
            headers['Content-Type'] = content_type
        req = urllib.request.Request(self.base + path, data=data, method=method, headers=headers)
        try:
            with urllib.request.urlopen(req, timeout=60) as response:
                result = json.load(response)
        except urllib.error.HTTPError as exc:
            result = json.load(exc)
            raise RuntimeError(f'{method} {path}: {result.get("code")} {result.get("message")}') from None
        return result['data']

    def upload(self, purpose: str, name: str, content: bytes, mime: str) -> str:
        boundary = 'paw' + uuid.uuid4().hex
        data = (f'--{boundary}\r\nContent-Disposition: form-data; name="purpose"\r\n\r\n{purpose}\r\n'
                f'--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="{name}"\r\n'
                f'Content-Type: {mime}\r\n\r\n').encode() + content + f'\r\n--{boundary}--\r\n'.encode()
        return self.call('POST', '/admin/files', raw=data,
                         content_type='multipart/form-data; boundary=' + boundary)['id']


def png(width: int, height: int, rgb: tuple[int, int, int]) -> bytes:
    """纯标准库生成单色 PNG，作为模拟检疫证明图片。"""
    def chunk(kind: bytes, payload: bytes) -> bytes:
        return struct.pack('>I', len(payload)) + kind + payload + struct.pack('>I', zlib.crc32(kind + payload))
    rows = b''.join(b'\x00' + bytes(rgb) * width for _ in range(height))
    return (b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', struct.pack('>IIBBBBB', width, height, 8, 2, 0, 0, 0))
            + chunk(b'IDAT', zlib.compress(rows)) + chunk(b'IEND', b''))


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument('--base', default='http://127.0.0.1:8190/api/v1')
    args = parser.parse_args()
    api = Api(args.base)
    captcha = api.call('GET', '/auth/graph-captcha')
    if captcha.get('captchaId') != 'demo-acceptance-only':
        raise SystemExit('目标不是隔离验收环境，拒绝写入')
    api.token = api.call('POST', '/admin/auth/login', {'username': 'admin', 'password': ADMIN_PASSWORD})['accessToken']

    # 新库门店营业时间默认是"请联系门店确认"，不能预约；按接口要求设成固定格式。
    shop = api.call('GET', '/admin/shop')
    fields = ('name,address,latitude,longitude,coordinateSystem,phone,wechat,businessHours,pickupInstructions,'
              'version,banners,paymentTimeoutMinutes,pickupRetentionHours,exchangeEnabled').split(',')
    api.call('PUT', '/admin/shop', {**{k: shop[k] for k in fields if shop.get(k) is not None},
                                    'coordinateSystem': 'gcj02', 'businessHours': '每天 10:00–20:00'})

    for kind, title, extra in (('live_pet_trade', '活体宠物交易须知', {'healthGuaranteeDays': 7}),
                               ('pickup_confirmation', '到店交付确认', {})):
        agreement = api.call('POST', '/admin/agreements', {
            'type': kind, 'version': 'ci-' + uuid.uuid4().hex[:8], 'title': title,
            'content': f'# {title}\n\n模拟协议，仅供自动化验收，不具备法律效力。', **extra})
        api.call('POST', f'/admin/agreements/{agreement["id"]}/publish', {'reviewConfirmed': True})

    photo = api.upload('pet_image', 'white-cat.webp',
                       (ROOT / 'frontend/public/images/pets/white-cat.webp').read_bytes(), 'image/webp')
    proof = api.upload('quarantine_public', 'proof.png', png(320, 200, (230, 230, 230)), 'image/png')
    pet = api.call('POST', '/admin/pets', {
        'name': '奶霜（示例）', 'category': 'cat', 'breed': '英国短毛猫', 'priceAmount': 300000,
        'gender': 'female', 'ageMonths': 6, 'weightKg': 3.1, 'color': '纯白色',
        'personalityTags': ['安静'], 'vaccineStatus': '模拟接种记录，仅供测试。', 'dewormStatus': '模拟驱虫记录，仅供测试。',
        'description': '自动化验收用示例宠物。', 'feedingNotes': '仅供测试。', 'healthDescription': '模拟健康说明，仅供测试。',
        'imageFileIds': [photo],
        'quarantine': {'certificateNo': 'DEMO-CI-001', 'publicImageFileIds': [proof], 'originalFileIds': []}})
    api.call('POST', f'/admin/pets/{pet["id"]}/publish', {
        'version': pet['version'], 'healthyForSale': True, 'quarantineVerified': True, 'reviewNote': '自动化验收模拟审核'})
    print(json.dumps({'agreements': 2, 'petId': pet['id']}, ensure_ascii=False))


if __name__ == '__main__':
    main()
