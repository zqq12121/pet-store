#!/usr/bin/env python3
"""生成带测试标记的宠物材料；--apply 才备份并接入当前 Compose 示例档案。"""
import argparse
import base64
from datetime import datetime, timedelta
import email.utils
import hashlib
import hmac
import importlib.util
import json
from pathlib import Path
import random
import re
import subprocess
import sys
import urllib.error
import urllib.request
import uuid
import xml.etree.ElementTree as ET
from zoneinfo import ZoneInfo

from pypdf import PdfReader
from reportlab.lib.pagesizes import A4
from reportlab.pdfbase import pdfmetrics
from reportlab.pdfbase.ttfonts import TTFont
from reportlab.pdfgen import canvas

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / 'output/demo-materials'
MARKER = '常见猫狗示例数据 v1'
COMPOSE = ['docker', 'compose', '--project-directory', str(ROOT)]
MYSQL = [*COMPOSE, 'exec', '-T', 'mysql', 'sh', '-c',
         'MYSQL_PWD="$MYSQL_PASSWORD" exec mysql --default-character-set=utf8mb4 '
         '-u"$MYSQL_USER" "$MYSQL_DATABASE" --batch --raw --skip-column-names']


def sql_text(value):
    """业务文本使用 UTF-8 十六进制字面量，避免引号及换行改变 SQL 语义。"""
    # 与迁移后的业务表一致，避免 MySQL 8 默认排序规则和旧表比较时报错。
    return 'CONVERT(0x' + str(value).encode().hex() + ' USING utf8mb4) COLLATE utf8mb4_unicode_ci'


def mysql(sql):
    result = subprocess.run(MYSQL, input=sql, text=True, capture_output=True)
    if result.returncode:
        raise RuntimeError(result.stderr.strip())
    return result.stdout


def snapshot():
    query = f"""SELECT JSON_OBJECT('id',p.id,'name',p.name,'category',p.category,
        'breed',p.breed,'ownerId',p.owner_id,'status',p.status,'version',p.version,
        'ageMonths',p.age_months,'imageCount',(SELECT COUNT(*) FROM pet_images WHERE parent_id=p.id),
        'orderCount',(SELECT COUNT(*) FROM orders WHERE pet_id=p.id)) FROM pets p
        WHERE LOCATE({sql_text(MARKER)},p.description)>0 ORDER BY p.id;"""
    pets = [json.loads(line) for line in mysql(query).splitlines()]
    if not pets or any(p['status'] not in ('off', 'on_sale') or p['orderCount'] or not p['imageCount'] for p in pets):
        raise SystemExit('示例档案为空、缺少照片或已关联订单；停止，保留现有数据。')
    if any('示例' not in p['name'] for p in pets):
        raise SystemExit('目标包含未标注示例的宠物，停止。')
    return pets


def page(pdf, title, rows, number, total):
    """每页同时保留大水印、页首及页脚测试声明，不模仿官方证件。"""
    width, height = A4
    pdf.setFillColorRGB(.97, .96, .93)
    pdf.rect(0, 0, width, height, fill=1, stroke=0)
    pdf.setFont('DemoChinese', 13)
    pdf.setFillColorRGB(.70, .24, .10)
    pdf.drawString(40, height - 48, '茸茸星球 / 模拟资料，仅供测试 / DEMO ONLY')
    pdf.setFillColorRGB(.15, .15, .15)
    pdf.setFont('DemoChinese', 22)
    pdf.drawString(40, height - 94, title)
    pdf.saveState()
    pdf.translate(width / 2, height / 2)
    pdf.rotate(30)
    pdf.setFillColorRGB(.87, .70, .63)
    pdf.setFont('DemoChinese', 44)
    pdf.drawCentredString(0, 0, '模拟资料  仅供测试')
    pdf.restoreState()
    y = height - 146
    for text in rows:
        pdf.setFont('DemoChinese', 13)
        pdf.setFillColorRGB(.12, .12, .12)
        # 固定行宽；长文本在字符边界换行，保证中文不会越出页面。
        line = ''
        for char in text:
            if pdfmetrics.stringWidth(line + char, 'DemoChinese', 13) > width - 80:
                pdf.drawString(40, y, line)
                y -= 23
                line = ''
            line += char
        pdf.drawString(40, y, line)
        y -= 35
    if y < 82:
        raise ValueError('内容超出单页范围：' + title)
    pdf.setFont('DemoChinese', 11)
    pdf.setFillColorRGB(.70, .24, .10)
    pdf.drawString(40, 48, '非官方材料，无真实接种、检疫或诊疗效力。')
    pdf.drawRightString(width - 40, 28, f'{number} / {total}')
    pdf.showPage()


def generate(pets, today):
    OUT.mkdir(parents=True, exist_ok=True)
    # 嵌入本机中文字体，PDF 在其他电脑上打开也不依赖额外中文语言包。
    pdfmetrics.registerFont(TTFont('DemoChinese', '/System/Library/Fonts/Supplemental/Arial Unicode.ttf'))
    package = OUT / '茸茸星球-模拟资料包.pdf'
    pdf = canvas.Canvas(str(package), pagesize=A4, invariant=1)
    pdf.setTitle('茸茸星球模拟资料包 - 仅供测试')
    assets, statements = [], ['START TRANSACTION;', 'SELECT id FROM business_lock WHERE id=1 FOR UPDATE;']
    expected = ' OR '.join(f"(p.id={sql_text(p['id'])} AND p.version={p['version']})" for p in pets)
    # 同库业务锁内再次检查版本和订单，防止准备材料期间宠物被预约后仍被改写。
    statements += ['CREATE TEMPORARY TABLE demo_seed_guard(ok INT CHECK(ok=1));',
                   f"INSERT INTO demo_seed_guard SELECT IF(COUNT(*)={len(pets)},1,0) FROM pets p "
                   f"WHERE ({expected}) AND p.status IN ('off','on_sale') "
                   f"AND NOT EXISTS(SELECT 1 FROM orders o WHERE o.pet_id=p.id);"]
    now = datetime.now(ZoneInfo('Asia/Shanghai')).isoformat(timespec='seconds')
    expiry = (today + timedelta(days=365)).isoformat()
    # 同一日、同一示例生成相同编号和随机记录；重复执行不产生重复附件。
    recommended = {'cat': 0, 'dog': 0}
    for index, pet in enumerate(pets, 1):
        rng = random.Random(pet['id'] + today.isoformat())
        code = f'DEMO-{today:%Y%m%d}-{index:03d}'
        dates = [(today - timedelta(days=days + rng.randrange(0, 5))).isoformat() for days in (65, 40, 15)]
        vaccine = '模拟接种记录：' + '、'.join(dates) + '；批号 DEMO-VAX-' + str(rng.randrange(1000, 9999)) + '，仅供测试。'
        deworm = '模拟驱虫记录：' + dates[-1] + '；批号 DEMO-DEW-' + str(rng.randrange(1000, 9999)) + '，仅供测试。'
        rows = [f"示例宠物：{pet['name']} / {pet['breed']} / {pet['ageMonths']} 个月",
                '材料编号：' + code, '虚构记录单位：茸茸星球测试资料中心（虚构）',
                '模拟疫苗记录：', *[f'  第 {i} 次示例接种：{d} / 示例疫苗 A / 仅供测试' for i, d in enumerate(dates, 1)],
                deworm, '模拟健康检查：外观与精神状态正常（虚构结果）。',
                '模拟检疫记录：示例校验通过，非官方检疫结论。',
                '测试有效期：' + expiry, '所有日期、批号和结论均为测试数据，不构成接种计划。']
        page(pdf, '模拟宠物健康与检疫记录', rows, index, len(pets) + 4)
        files = []
        for purpose in ('quarantine_public', 'quarantine_original'):
            fid = 'file_' + uuid.uuid5(uuid.NAMESPACE_URL, code + pet['id'] + purpose).hex
            files.append(fid)
            assets.append({'id': fid, 'purpose': purpose, 'petId': pet['id'], 'page': index,
                           'ownerId': pet['ownerId'], 'originalName': code + '-' + purpose + '.png'})
        # 导入时仍保护版本与订单关联；只修改这批明确标注的示例档案。
        statements.append(f"UPDATE pets SET vaccine_status={sql_text(vaccine)},deworm_status={sql_text(deworm)},"
                          f"health_description={sql_text('模拟体检与检疫资料已补齐，仅用于测试，无真实健康结论。')},"
                          f"status='on_sale',is_recommended={int(recommended[pet['category']] < 2)},"
                          f"recommendation_order={index},reviewed_at={sql_text(now)},published_at={sql_text(now)},"
                          f"last_sale_review_note={sql_text('DEMO：模拟材料导入，仅用于演示预约流程，不代表真实核验。')},"
                          f"updated_at={sql_text(now)},version=version+1 WHERE id={sql_text(pet['id'])} "
                          f"AND version={pet['version']} AND status IN ('off','on_sale');")
        recommended[pet['category']] += 1
        statements.append(f"INSERT INTO pet_quarantine_certificates(parent_id,certificate_no,valid_until) VALUES "
                          f"({sql_text(pet['id'])},{sql_text(code)},{sql_text(expiry)}) ON DUPLICATE KEY UPDATE "
                          f"certificate_no={sql_text(code)},valid_until={sql_text(expiry)};")
        for table, fid in zip(('pet_quarantine_public_files', 'pet_quarantine_original_files'), files):
            statements.extend([f"DELETE FROM {table} WHERE parent_id={sql_text(pet['id'])};",
                               f"INSERT INTO {table}(parent_id,position,file_id) VALUES ({sql_text(pet['id'])},0,{sql_text(fid)});"])
    supplemental = [
        ('diagnosis-demo.png', '模拟售后诊断附件', ['示例个体：测试宠物 DEMO-PET', '示例编号：DEMO-DIAG-001',
         '虚构记录单位：茸茸星球测试资料中心（虚构）', '模拟主诉：食欲下降（测试情境）。',
         '模拟检查：附件格式和字段展示测试，无真实诊断。', '测试用途：在本人演示订单下上传为 diagnosis 附件。']),
        ('after-sale-evidence-demo.png', '模拟售后证据附件', ['示例编号：DEMO-EVIDENCE-001',
         '示例物品：宠物交付用品（虚构）。', '模拟说明：包装外观异常，用于售后附件展示。',
         '示例金额：88.00 元，无真实付款记录。', '测试用途：在演示订单下上传为 after_sale_evidence 附件。'])]
    for offset, (_, title, rows) in enumerate(supplemental, 1):
        page(pdf, title, rows, len(pets) + offset, len(pets) + 4)
    agreements = []
    for offset, kind in enumerate(('live_pet_trade', 'pickup_confirmation'), 3):
        title = '活体宠物交易须知（模拟测试）' if kind == 'live_pet_trade' else '到店健康确认书（模拟测试）'
        lines = ['【模拟协议，仅供项目测试，不构成真实交易约定】', '1. 本网站为茸茸星球个人练习项目，地址与宠物档案均为示例。',
                 '2. 预约模拟到店安排，线上不发生扣款；价格仅用于金额计算测试。',
                 '3. 疫苗、驱虫、检疫及诊断材料均标注 DEMO，不能用于实际证明。',
                 '4. 测试到店时间为每天 08:00-17:00，可模拟确认、取消与交付。',
                 '5. 模拟健康保障期为 7 天，售后处理用于状态与权限验证。',
                 '6. 演示时使用虚构联系人，不需要提交真实身份证或医疗材料。',
                 '7. 本版本只影响新建演示订单；历史签署版本及快照保留。']
        page(pdf, title, lines, len(pets) + offset, len(pets) + 4)
        content = '\n'.join(lines)
        version = f'demo-{today:%Y%m%d}'
        aid = 'agreement_' + uuid.uuid5(uuid.NAMESPACE_URL, kind + version).hex
        agreements.append(aid)
        statements.append(f"UPDATE agreement_versions SET status='retired' WHERE type={sql_text(kind)} "
                          f"AND status='published' AND id<>{sql_text(aid)};")
        vals = [kind + ':' + version, aid, pets[0]['ownerId'], 'published', version, now, now, kind, title, content, 'plain', hashlib.sha256(content.encode()).hexdigest()]
        statements.append('INSERT INTO agreement_versions(business_key,id,owner_id,status,version,created_at,updated_at,type,title,content,content_format,content_hash,health_guarantee_days,published_at) VALUES (' +
                          ','.join(map(sql_text, vals)) + ',' + ('7' if kind == 'live_pet_trade' else 'NULL') + ',' + sql_text(now) + ") ON DUPLICATE KEY UPDATE status='published';")
    pdf.save()
    if len(PdfReader(package).pages) != len(pets) + 4:
        raise ValueError('资料包页数不正确')
    rendering = subprocess.run(['pdftoppm', '-r', '110', '-png', str(package), str(OUT / 'page')],
                               check=True, capture_output=True, text=True)
    if rendering.stderr.strip():
        raise ValueError('PDF 渲染存在警告，停止导入：' + rendering.stderr)
    rendered = sorted(OUT.glob('page-*.png'))
    if len(rendered) != len(pets) + 4:
        raise ValueError('渲染页数不正确')
    for offset, (filename, _, _) in enumerate(supplemental):
        (OUT / filename).write_bytes(rendered[len(pets) + offset].read_bytes())
    asset_sql = []
    for asset in assets:
        path = rendered[asset['page'] - 1]
        asset.update(path=str(path), sha256=hashlib.sha256(path.read_bytes()).hexdigest())
        values = [asset['id'], asset['ownerId'], 'ready', now, now, asset['originalName'], 'image/png']
        asset_sql.append('INSERT INTO file_assets(id,owner_id,status,version,created_at,updated_at,original_name,mime_type,size_bytes,purpose,visibility,public_url) VALUES (' +
                         ','.join(map(sql_text, values[:3])) + ',1,' + ','.join(map(sql_text, values[3:])) + ',' + str(path.stat().st_size) + ',' +
                         sql_text(asset['purpose']) + ',' + sql_text('public' if asset['purpose'] == 'quarantine_public' else 'private') + ',' +
                         (sql_text('/api/v1/media/' + asset['id']) if asset['purpose'] == 'quarantine_public' else 'NULL') + ') ON DUPLICATE KEY UPDATE id=id;')
    statements[4:4] = asset_sql
    (OUT / 'import.sql').write_text('\n'.join(statements + ['COMMIT;']) + '\n')
    manifest = {'date': today.isoformat(), 'pets': pets, 'assets': assets, 'agreements': agreements, 'package': str(package)}
    (OUT / 'manifest.json').write_text(json.dumps(manifest, ensure_ascii=False, indent=2))
    return manifest


def upload_oss(assets):
    """沿用现有私有 OSS 前缀，拒绝覆盖内容不同的同名对象，上传后回读。"""
    spec = importlib.util.spec_from_file_location('compose_env', ROOT / 'scripts/init-compose-env.py')
    env_module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(env_module)
    env = env_module.read_env(ROOT / '.env')
    if env.get('PAW_OSS_ENABLED', '').lower() != 'true':
        return
    bucket, endpoint = env['PAW_OSS_BUCKET'], env['PAW_OSS_ENDPOINT'].removeprefix('https://')
    if not re.fullmatch(r'[a-z0-9-]+', bucket) or not re.fullmatch(r'oss-[a-z0-9-]+\.aliyuncs\.com', endpoint):
        raise ValueError('OSS 配置格式不正确')
    def request(method, key, data=None):
        date = email.utils.formatdate(usegmt=True)
        md5 = base64.b64encode(hashlib.md5(data).digest()).decode() if data is not None else ''
        mime = 'image/png' if data is not None else ''
        extra = 'x-oss-object-acl:private\n' if data is not None else ''
        message = f'{method}\n{md5}\n{mime}\n{date}\n{extra}/{bucket}/{key}'
        signature = base64.b64encode(hmac.new(env['OSS_ACCESS_KEY_SECRET'].encode(), message.encode(), hashlib.sha1).digest()).decode()
        headers = {'Date': date, 'Authorization': 'OSS ' + env['OSS_ACCESS_KEY_ID'] + ':' + signature}
        if data is not None:
            headers.update({'Content-MD5': md5, 'Content-Type': mime, 'x-oss-object-acl': 'private'})
        return urllib.request.urlopen(urllib.request.Request(f'https://{bucket}.{endpoint}/{key}', data=data, headers=headers, method=method), timeout=30)
    for asset in assets:
        key, data = 'warmpaw/files/' + asset['id'], Path(asset['path']).read_bytes()
        try:
            with request('GET', key) as response:
                existing = response.read()
            if existing != data:
                raise ValueError('已有 OSS 对象内容不同：' + asset['id'])
        except urllib.error.HTTPError as error:
            if error.code != 404:
                raise
            with request('PUT', key, data):
                pass
        with request('GET', key) as response:
            if response.read() != data:
                raise ValueError('OSS 回读不一致')
        with request('GET', key + '?acl') as response:
            if ET.fromstring(response.read()).findtext('AccessControlList/Grant') != 'private':
                raise ValueError('OSS 对象必须为 private')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--apply', action='store_true')
    args = parser.parse_args()
    pets = snapshot()
    manifest = generate(pets, datetime.now(ZoneInfo('Asia/Shanghai')).date())
    if args.apply:
        # 文件生成/检查后再备份与导入，不创建测试订单、不发送短信。
        subprocess.run([sys.executable, str(ROOT / 'scripts/backup-compose.py')], check=True)
        current = snapshot()
        if [(p['id'], p['version']) for p in current] != [(p['id'], p['version']) for p in pets]:
            raise SystemExit('示例档案版本已变化，停止导入，请重新生成。')
        upload_oss(manifest['assets'])
        for asset in manifest['assets']:
            subprocess.run([*COMPOSE, 'cp', asset['path'], 'admin-server:/app/data/files/' + asset['id']], check=True, capture_output=True)
        subprocess.run([*COMPOSE, 'exec', '-T', '-u', 'root', 'admin-server', 'chown', 'app:app',
                        *['/app/data/files/' + asset['id'] for asset in manifest['assets']]], check=True, capture_output=True)
        mysql((OUT / 'import.sql').read_text())
    print(json.dumps({'applied': args.apply, 'pets': len(pets), 'files': len(manifest['assets']), 'agreements': 2,
                      'package': manifest['package'], 'samples': ['diagnosis-demo.png', 'after-sale-evidence-demo.png']}, ensure_ascii=False))


if __name__ == '__main__':
    main()
