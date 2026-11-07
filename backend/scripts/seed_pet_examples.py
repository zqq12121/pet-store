#!/usr/bin/env python3
"""向本机 MySQL 网站导入常见猫狗示例档案；保留未上架状态，不改已有商品。"""
import json
import pathlib
import urllib.error
import urllib.request
import uuid

ROOT = pathlib.Path(__file__).resolve().parents[1]
BASE = 'http://localhost/api/v1'
MARKER = '常见猫狗示例数据 v1'

# 售价单位为元，写入接口时转换为分；年龄、体重、性格均为虚构个体信息。
# 分类、品种、昵称、示意图、价格、月龄、体重、毛色、性格、养护重点。
SPECS = [
    # 每条对应 frontend/public/images/pets 下的一张照片，毛色、性格按照片填写。
    ('dog', '威尔士柯基犬', '奶糖', 'corgi-sit', 4800, 8, 10.0, '黄白双色', ['活泼', '友善'], '控制体重，减少频繁跳跃与上下楼，保持适量运动。'),
    ('cat', '暹罗猫', '咖啡', 'siamese-garden', 2200, 8, 3.0, '海豹重点色', ['好奇', '爱互动'], '提供充分陪伴与益智玩具，注意室内温度。'),
    ('dog', '金毛寻回犬', '松饼', 'golden-ball', 3600, 3, 7.5, '浅金色', ['活泼', '爱玩球'], '安排散步与基础训练，幼犬避免过度负重运动。'),
    ('cat', '英国短毛猫', '年糕', 'british-blue', 2800, 10, 4.2, '蓝色', ['安静', '亲人'], '注意体重管理，定时互动，定期梳理短毛。'),
    ('dog', '边境牧羊犬', '奥利奥', 'border-collie-sit', 3800, 12, 15.0, '黑白双色', ['聪明', '专注'], '安排每日运动与脑力训练，避免长期缺乏活动。'),
    ('cat', '西伯利亚猫', '蜜桃', 'ginger-longhair', 6000, 8, 3.8, '红虎斑加白', ['温和', '好奇'], '定期梳理浓密被毛，提供运动空间；不承诺低致敏。'),
    ('dog', '博美犬', '米粒', 'pomeranian', 3800, 8, 2.2, '灰白色', ['警觉', '活泼'], '梳理双层被毛，外出使用合适胸背，避免高处跳落。'),
    ('cat', '孟加拉猫', '金沙', 'bengal', 8000, 9, 3.6, '金色虎纹', ['活泼', '好奇'], '精力旺盛，准备猫爬架和逗猫玩具，每天安排足量游戏。'),
    ('dog', '拉布拉多寻回犬', '可乐', 'labrador', 3200, 3, 8.0, '奶油黄色', ['温和', '友善'], '控制食量，使用正向奖励训练，提供充足活动时间。'),
    ('cat', '美国短毛猫', '银豆', 'american-shorthair', 2600, 8, 3.5, '银色虎斑', ['好奇', '友善'], '提供猫抓板与攀爬空间，安排每日逗猫游戏。'),
    ('dog', '比熊犬', '棉花', 'bichon', 3200, 6, 3.5, '白色', ['温和', '亲人'], '规律梳毛和美容，保持眼周清洁，循序训练独处。'),
    ('cat', '英国短毛猫', '雪团', 'white-cat', 3000, 9, 3.4, '纯白色', ['安静', '温和'], '注意体重管理，白色被毛需定期清洁眼周与耳朵。'),
    ('dog', '澳大利亚牧羊犬', '星星', 'aussie', 5500, 10, 18.0, '蓝陨石色', ['精力充沛', '聪明'], '每天需要大量运动和训练，适合有户外活动时间的家庭。'),
    ('cat', '中华田园猫', '橘宝', 'ginger-fountain', 500, 10, 4.0, '橘色虎斑', ['亲人', '慵懒'], '做好封窗，控制食量防止发胖，领养与购买都应核实来源。'),
    ('dog', '金毛寻回犬', '麦芽', 'golden-lake', 3600, 4, 9.0, '金色', ['友善', '亲人'], '安排散步与基础训练，幼犬避免过度负重运动。'),
    ('cat', '暹罗猫', '拿铁', 'siamese-sofa', 2400, 6, 2.6, '海豹重点色', ['亲人', '爱说话'], '提供充分陪伴与益智玩具，注意室内温度。'),
    ('dog', '边境牧羊犬', '芝麻', 'border-collie-lavender', 3800, 10, 14.0, '黑白双色', ['爱互动', '活泼'], '安排每日运动与脑力训练，避免长期缺乏活动。'),
    ('cat', '缅因猫', '栗子', 'maine-coon', 6800, 12, 6.0, '棕虎斑', ['友善', '沉稳'], '准备稳固的大型猫爬架，定期梳毛，关注生长和关节。'),
    ('dog', '威尔士柯基犬', '豆包', 'corgi-tricolor', 4600, 12, 11.0, '三色', ['开朗', '友善'], '控制体重，减少频繁跳跃与上下楼，保持适量运动。'),
    ('cat', '中华狸花猫', '狸狸', 'tabby-basket', 600, 7, 3.2, '棕色虎斑', ['独立', '好奇'], '做好封窗，安排日常互动，领养与购买都应核实来源。'),
]


def request(method, path, body=None, token=None, raw=None, content_type=None):
    """沿用后台接口校验与审计；错误信息不包含密码和访问令牌。"""
    headers = {'Idempotency-Key': str(uuid.uuid4())}
    if token:
        headers['Authorization'] = 'Bearer ' + token
    data = raw
    if body is not None:
        data = json.dumps(body, ensure_ascii=False).encode()
        content_type = 'application/json'
    if content_type:
        headers['Content-Type'] = content_type
    req = urllib.request.Request(BASE + path, data=data, method=method, headers=headers)
    try:
        with urllib.request.urlopen(req, timeout=20) as response:
            result = json.load(response)
    except urllib.error.HTTPError as exc:
        result = json.load(exc)
        raise RuntimeError(f'{method} {path}: {result.get("code")} {result.get("message")}') from None
    if result['code'] != 'OK':
        raise RuntimeError(result['message'])
    return result['data']


def upload(photo, token):
    """复用项目已有图片作界面示意，不生成或上传检疫证明。"""
    content = (ROOT.parent / 'frontend/public/images/pets' / f'{photo}.webp').read_bytes()
    boundary = 'paw' + uuid.uuid4().hex
    data = (f'--{boundary}\r\nContent-Disposition: form-data; name="purpose"\r\n\r\npet_image\r\n'
            f'--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="example-{photo}.webp"\r\n'
            'Content-Type: image/webp\r\n\r\n').encode() + content + f'\r\n--{boundary}--\r\n'.encode()
    return request('POST', '/admin/files', token=token, raw=data,
                   content_type='multipart/form-data; boundary=' + boundary)['id']


def main():
    password = (ROOT / 'data/local-admin-password.txt').read_text().strip()
    token = request('POST', '/admin/auth/login', {
        'username': 'admin', 'password': password})['accessToken']
    created, skipped, images, report = 0, 0, {}, []
    try:
        # 分页检查标记，重复执行时跳过已导入档案，不覆盖用户后续编辑。
        existing = {}
        page = 1
        while True:
            batch = request('GET', f'/admin/pets?page={page}&pageSize=100', token=token)
            existing.update((p['name'], p) for p in batch['items'])
            if page * 100 >= batch['total']:
                break
            page += 1
        for index, (category, breed, name, photo, price, age, weight, color, tags, care) in enumerate(SPECS):
            name += '（示例）'
            if name in existing:
                pet = request('GET', '/admin/pets/' + existing[name]['id'], token=token)
                if MARKER not in pet['description']:
                    raise RuntimeError('存在同名非本批次商品，停止以避免混淆：' + name)
                skipped += 1
            else:
                if photo not in images:
                    images[photo] = upload(photo, token)
                body = {
                    'name': name, 'category': category, 'breed': breed, 'priceAmount': price * 100,
                    'gender': 'female' if index % 2 else 'male', 'ageMonths': age,
                    'birthDate': None, 'weightKg': weight, 'color': color, 'personalityTags': tags,
                    'vaccineStatus': '示例档案：尚未录入真实接种记录，待核验。',
                    'dewormStatus': '示例档案：尚未录入真实驱虫记录，待核验。',
                    'description': f'{MARKER}。{name}是一只虚构的{breed}，用于后台管理与页面联调。'
                                   '价格为演示定价，不代表市场报价；照片仅供参考，不代表真实个体。',
                    'feedingNotes': care + '新到家时保持原有饮食并逐步过渡，具体方案请咨询兽医。',
                    'healthDescription': '示例个体，无真实体检或检疫结论；补充真实材料并审核后方可上架。',
                    'imageFileIds': [images[photo]], 'videoFileId': None, 'quarantine': None,
                    'isRecommended': False, 'recommendationOrder': 0,
                }
                pet = request('POST', '/admin/pets', body, token)
                # 逐条回读持久化字段，验证接口没有只返回未落库的结果。
                saved = request('GET', '/admin/pets/' + pet['id'], token=token)
                for field in ('name', 'category', 'breed', 'priceAmount', 'imageFileIds'):
                    if saved[field] != body[field]:
                        raise RuntimeError('回读字段不一致：' + field)
                if saved['status'] != 'off':
                    raise RuntimeError('示例档案未保持未上架状态')
                created += 1
            report.append({key: pet[key] for key in ('id', 'name', 'category', 'breed', 'priceAmount', 'status')})
        target = ROOT / 'target/pet-examples-import.json'
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n')
        print(json.dumps({'created': created, 'skipped': skipped, 'verified': len(report),
                          'report': str(target)}, ensure_ascii=False))
    finally:
        request('POST', '/admin/auth/logout', token=token)


if __name__ == '__main__':
    main()
