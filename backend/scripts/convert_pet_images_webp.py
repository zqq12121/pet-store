#!/usr/bin/env python3
"""把已上传的宠物照片（JPEG/PNG）转成 WebP 并替换到宠物档案；默认只预览，加 --apply 才写入。

全程走后台接口：重新上传为 pet_image，再用 PUT /admin/pets/{id} 只替换 imageFileIds，
其余字段（含检疫证明）按 GET 返回值原样提交，因此不会触发"健康信息变更→下架复审"。
已预约/已售的宠物接口不允许编辑，直接跳过；检疫证明已过期的在售宠物也跳过（保存会被自动下架）。
任何一只宠物保存后状态意外变化，立即停止，不再继续处理。

需要本机安装 Pillow（pip install pillow）。管理员密码读环境变量 PAW_ADMIN_PASSWORD，未设置时交互输入。

示例：
  python3 backend/scripts/convert_pet_images_webp.py --base https://petnest.me/api/v1
  python3 backend/scripts/convert_pet_images_webp.py --base https://petnest.me/api/v1 --apply
"""
import argparse
import datetime
import getpass
import io
import json
import os
import sys
import urllib.error
import urllib.parse
import urllib.request
import uuid
from typing import Any

try:
    from PIL import Image, ImageOps
except ImportError:
    sys.exit('需要 Pillow：pip install pillow')

WRITABLE = ('name,category,breed,priceAmount,gender,ageMonths,birthDate,weightKg,color,personalityTags,'
            'vaccineStatus,dewormStatus,description,feedingNotes,healthDescription,imageFileIds,videoFileId,'
            'quarantine,isRecommended,recommendationOrder,version').split(',')


class Api:
    def __init__(self, base: str) -> None:
        self.base = base.rstrip('/')
        self.origin = '{0.scheme}://{0.netloc}'.format(urllib.parse.urlsplit(self.base))
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
        if result['code'] != 'OK':
            raise RuntimeError(f'{method} {path}: {result["message"]}')
        return result['data']

    def download(self, url: str) -> bytes:
        with urllib.request.urlopen(urllib.parse.urljoin(self.origin, url), timeout=60) as response:
            return response.read()

    def upload(self, name: str, content: bytes) -> str:
        boundary = 'paw' + uuid.uuid4().hex
        data = (f'--{boundary}\r\nContent-Disposition: form-data; name="purpose"\r\n\r\npet_image\r\n'
                f'--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="{name}"\r\n'
                'Content-Type: image/webp\r\n\r\n').encode() + content + f'\r\n--{boundary}--\r\n'.encode()
        return self.call('POST', '/admin/files', raw=data,
                         content_type='multipart/form-data; boundary=' + boundary)['id']


def to_webp(content: bytes, max_edge: int, quality: int) -> bytes:
    """按 EXIF 方向摆正、长边缩到 max_edge 以内，编码为 WebP。"""
    image = ImageOps.exif_transpose(Image.open(io.BytesIO(content)))
    image.thumbnail((max_edge, max_edge), Image.LANCZOS)
    if image.mode not in ('RGB', 'RGBA'):
        image = image.convert('RGBA' if 'A' in image.getbands() else 'RGB')
    out = io.BytesIO()
    image.save(out, 'WEBP', quality=quality, method=6)
    return out.getvalue()


def quarantine_valid(pet: dict[str, Any]) -> bool:
    """与后端 CatalogReader.validQuarantine 一致：有公开图且未过期（按北京时间）。"""
    q = pet.get('quarantine') or {}
    if not q.get('publicImageFileIds'):
        return False
    until = q.get('validUntil')
    today = datetime.datetime.now(datetime.timezone(datetime.timedelta(hours=8))).date()
    return until is None or datetime.date.fromisoformat(until) >= today


def all_pets(api: Api) -> list[dict[str, Any]]:
    pets: list[dict[str, Any]] = []
    page = 1
    while True:
        data = api.call('GET', f'/admin/pets?page={page}&pageSize=100')
        pets += data['items']
        if len(pets) >= data['total'] or not data['items']:
            return pets
        page += 1


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument('--base', required=True, help='接口根地址，如 https://petnest.me/api/v1')
    parser.add_argument('--username', default=os.environ.get('PAW_ADMIN_USERNAME', 'admin'))
    parser.add_argument('--max-edge', type=int, default=1920)
    parser.add_argument('--quality', type=int, default=75)
    parser.add_argument('--apply', action='store_true', help='真正上传并替换；不加只预览')
    args = parser.parse_args()

    api = Api(args.base)
    password = os.environ.get('PAW_ADMIN_PASSWORD') or getpass.getpass(f'{args.username} 的密码：')
    api.token = api.call('POST', '/admin/auth/login', {'username': args.username, 'password': password})['accessToken']

    before_total = after_total = changed = 0
    for summary in all_pets(api):
        pet = api.call('GET', f'/admin/pets/{summary["id"]}')
        label = f'{pet["name"]}（{pet["id"]}，{pet["status"]}）'
        assets = {a['id']: a for a in pet.get('assets', [])}
        targets = [i for i in pet['imageFileIds']
                   if assets.get(i, {}).get('mimeType') in ('image/jpeg', 'image/png')]
        if not targets:
            continue
        if pet['status'] not in ('off', 'on_sale'):
            print(f'跳过 {label}：已预约或已售，接口不允许编辑')
            continue
        if pet['status'] == 'on_sale' and not quarantine_valid(pet):
            print(f'跳过 {label}：检疫证明无效或已过期，保存会被自动下架')
            continue

        replacements: dict[str, bytes] = {}
        for file_id in targets:
            original = api.download(assets[file_id]['publicUrl'])
            webp = to_webp(original, args.max_edge, args.quality)
            if len(webp) >= len(original):
                continue
            replacements[file_id] = webp
            before_total += len(original)
            after_total += len(webp)
            print(f'{label}：{file_id} {len(original) // 1024}KB → {len(webp) // 1024}KB')
        if not replacements or not args.apply:
            continue

        new_ids = {old: api.upload(f'{old}.webp', data) for old, data in replacements.items()}
        body = {k: pet[k] for k in WRITABLE if pet.get(k) is not None}
        body['imageFileIds'] = [new_ids.get(i, i) for i in pet['imageFileIds']]
        saved = api.call('PUT', f'/admin/pets/{pet["id"]}', body)
        if saved['status'] != pet['status']:
            sys.exit(f'停止：{label} 保存后状态变为 {saved["status"]}，请到后台检查后再继续')
        changed += 1

    mode = '已替换' if args.apply else '预览（加 --apply 才会写入）'
    print(f'{mode}：{changed if args.apply else "-"} 只宠物，照片 {before_total // 1024}KB → {after_total // 1024}KB')


if __name__ == '__main__':
    main()
