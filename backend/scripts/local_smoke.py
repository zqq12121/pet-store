#!/usr/bin/env python3
"""仅用于本机 local 模式：通过真实 HTTP 验证上传、上架、下单、付款和交付，不输出凭证。"""
import argparse
import json
import pathlib
import struct
import urllib.request
import urllib.error
import uuid
import zlib
import time

ROOT = pathlib.Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser()
parser.add_argument('--base', default='http://127.0.0.1:8080/api/v1')
parser.add_argument('--phone', default='13800138000')
args = parser.parse_args()
if not args.base.startswith(('http://127.0.0.1:', 'http://localhost:')):
    raise SystemExit('只允许连接本机开发服务')


def request(method, path, body=None, token=None, raw=None, content_type=None):
    headers = {'Idempotency-Key': str(uuid.uuid4())}
    if token:
        headers['Authorization'] = 'Bearer ' + token
    if raw is not None:
        data = raw
        headers['Content-Type'] = content_type
    elif body is not None:
        data = json.dumps(body, ensure_ascii=False).encode()
        headers['Content-Type'] = 'application/json'
    else:
        data = None
    req = urllib.request.Request(args.base + path, data=data, method=method, headers=headers)
    try:
        with urllib.request.urlopen(req, timeout=20) as res:
            value = json.load(res)
    except urllib.error.HTTPError as exc:
        value = json.load(exc)
        raise RuntimeError(f'{method} {path}: {value.get("code")} {value.get("message")}') from None
    if value['code'] != 'OK':
        raise RuntimeError(value['message'])
    return value['data']


def local_code(kind, identifier):
    return (ROOT / 'data' / 'local-inbox' / f'{kind}-{identifier}.txt').read_text().strip()


def upload(purpose, token):
    # 自行生成带醒目 TEST ONLY 字样的小 PNG，只作为测试证据，不能当作真实检疫材料。
    font = {'T': ['11111','00100','00100','00100','00100','00100','00100'],
            'E': ['11111','10000','10000','11110','10000','10000','11111'],
            'S': ['01111','10000','10000','01110','00001','00001','11110'],
            'O': ['01110','10001','10001','10001','10001','10001','01110'],
            'N': ['10001','11001','11001','10101','10011','10011','10001'],
            'L': ['10000','10000','10000','10000','10000','10000','11111'],
            'Y': ['10001','10001','01010','00100','00100','00100','00100']}
    width, height = 360, 100
    pixels = bytearray([255, 240, 220] * width * height)
    for index, char in enumerate('TEST ONLY'):
        for y, row in enumerate(font.get(char, ['00000'] * 7)):
            for x, bit in enumerate(row):
                if bit == '1':
                    for dy in range(5):
                        for dx in range(5):
                            offset = ((25 + y * 5 + dy) * width + 20 + index * 35 + x * 5 + dx) * 3
                            pixels[offset:offset + 3] = bytes([160, 40, 20])
    def chunk(kind, data):
        return struct.pack('>I', len(data)) + kind + data + struct.pack('>I', zlib.crc32(kind + data) & 0xffffffff)
    rows = b''.join(b'\0' + pixels[y * width * 3:(y + 1) * width * 3] for y in range(height))
    png = b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', struct.pack('>IIBBBBB', width, height, 8, 2, 0, 0, 0)) + chunk(b'IDAT', zlib.compress(rows)) + chunk(b'IEND', b'')
    boundary = 'paw' + uuid.uuid4().hex
    data = (f'--{boundary}\r\nContent-Disposition: form-data; name="purpose"\r\n\r\n{purpose}\r\n'
            f'--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="test-only.png"\r\nContent-Type: image/png\r\n\r\n').encode() + png + f'\r\n--{boundary}--\r\n'.encode()
    return request('POST', '/admin/files', token=token, raw=data, content_type='multipart/form-data; boundary=' + boundary)['id']


captcha = request('GET', '/auth/captchas?purpose=admin_login')
password = (ROOT / 'data' / 'local-admin-password.txt').read_text().strip()
admin = request('POST', '/admin/auth/login', {'username': 'admin', 'password': password,
                 'captchaId': captcha['captchaId'], 'captchaCode': local_code('captcha', captcha['captchaId'])})['accessToken']
for kind in ('live_pet_trade', 'pickup_confirmation'):
    versions = request('GET', '/admin/agreements?type=' + kind, token=admin)['items']
    if not any(item['status'] == 'published' for item in versions):
        body = {'type': kind, 'version': 'local-' + uuid.uuid4().hex[:12], 'title': '本地联调协议（非正式）',
                'content': '仅供本机开发测试，不构成实际交易、检疫或健康证明。'}
        if kind == 'live_pet_trade':
            body['healthGuaranteeDays'] = 7
        agreement = request('POST', '/admin/agreements', body, admin)
        request('POST', '/admin/agreements/' + agreement['id'] + '/publish', {'reviewConfirmed': True}, admin)

photo, proof = upload('pet_image', admin), upload('quarantine_public', admin)
pet = request('POST', '/admin/pets', {'name': '本地测试宠物', 'category': 'cat', 'breed': '测试品种', 'priceAmount': 10000,
        'gender': 'female', 'ageMonths': 6, 'weightKg': 2.5, 'color': '测试', 'vaccineStatus': '仅供联调，非真实记录',
        'dewormStatus': '仅供联调，非真实记录', 'description': 'HTTP 冒烟测试数据', 'feedingNotes': '仅供测试',
        'healthDescription': '不代表真实健康状态', 'imageFileIds': [photo], 'quarantine': {'certificateNo': 'TEST-ONLY',
        'publicImageFileIds': [proof], 'originalFileIds': [], 'validUntil': None}}, admin)
pet = request('POST', '/admin/pets/' + pet['id'] + '/publish', {'version': pet['version'], 'healthyForSale': True,
              'quarantineVerified': True, 'reviewNote': '仅对模拟材料进行开发测试'}, admin)
captcha = request('GET', '/auth/captchas?purpose=sms')
sms = request('POST', '/auth/sms-codes', {'phone': args.phone, 'purpose': 'login', 'captchaId': captcha['captchaId'],
              'captchaCode': local_code('captcha', captcha['captchaId'])})
buyer = request('POST', '/auth/sms-login', {'phone': args.phone, 'smsRequestId': sms['smsRequestId'],
                'smsCode': local_code('sms', sms['smsRequestId'])})['accessToken']
preview = request('POST', '/orders/preview', {'petId': pet['id']}, buyer)
order = request('POST', '/orders', {'petId': pet['id'], 'productVersion': preview['productVersion'], 'expectedAmount': preview['amount'],
                'contactName': '本地测试', 'contactPhone': args.phone, 'agreementVersion': preview['agreementVersion'],
                'agreementContentHash': preview['agreementContentHash'], 'agreementAccepted': True}, buyer)
order = request('POST', '/demo/orders/' + order['id'] + '/pay', {}, buyer)
assert order['status'] == 'paid'
# 遵守实际60秒短信限流，不为冒烟测试绕过业务规则。
print('上传、上架、下单和模拟支付通过；等待短信发送间隔后验证现场交付。', flush=True)
time.sleep(61)
sms = request('POST', '/orders/' + order['id'] + '/pickup-verification-codes', {}, buyer)
agreement = request('GET', '/agreements/current?type=pickup_confirmation')
confirmation = request('POST', '/orders/' + order['id'] + '/pickup-confirmations', {
    'smsRequestId': sms['smsRequestId'], 'smsCode': local_code('sms', sms['smsRequestId']),
    'confirmationVersion': agreement['version'], 'confirmationContentHash': agreement['contentHash'],
    'accepted': True, 'checks': {'mentalState': True, 'eyesAndNose': True, 'coat': True, 'excretion': True}}, buyer)
lookup = request('POST', '/admin/pickups/lookup', {'pickupCode': order['pickup']['code']}, admin)
assert lookup['pickupAllowed']
request('POST', '/admin/orders/' + order['id'] + '/pickup', {'confirmationId': confirmation['confirmationId'],
        'pickupCode': order['pickup']['code'], 'quarantineVerified': True}, admin)
order = request('GET', '/orders/' + order['id'], token=buyer)
assert order['status'] == 'completed' and order['pickup'] is None
assert 'phoneHash' not in order['pickupEvidence']
report = {'orderId': order['id'], 'petId': pet['id'], 'status': order['status'],
          'verified': ['HTTP登录', '图片上传', '协议发布', '宠物上架', '结算快照', '订单占用', '模拟支付', '自提凭证生成', '现场短信确认', '店主核销', '订单完成']}
print(json.dumps(report, ensure_ascii=False, indent=2))
