#!/usr/bin/env python3
"""备份本机 Docker mysql 的 warmpaw 专用库；凭证仅经标准输入传递。"""
from datetime import datetime
from pathlib import Path
import shlex
import subprocess

root = Path(__file__).resolve().parents[1]
values = {}
for line in (root / '.env').read_text().splitlines():
    if not line.strip() or line.startswith('#'):
        continue
    key, value = line.split('=', 1)
    values[key] = shlex.split(value)[0] if value else ''
# 固定范围，避免误备份其他数据库；不把数据库密码写进命令行或输出。
script = ('export MYSQL_PWD=' + shlex.quote(values['PAW_DB_PASSWORD']) + '\n'
          'exec mysqldump -h127.0.0.1 -u ' + shlex.quote(values['PAW_DB_USERNAME']) +
          ' --single-transaction --skip-lock-tables --no-tablespaces --set-gtid-purged=OFF --hex-blob warmpaw\n')
folder = root / 'data' / 'backups'
folder.mkdir(parents=True, exist_ok=True)
path = folder / ('warmpaw-' + datetime.now().strftime('%Y%m%d-%H%M%S') + '.sql')
with path.open('xb') as output:
    path.chmod(0o600)
    result = subprocess.run(['docker', 'exec', '-i', 'mysql', 'sh'], input=script.encode(), stdout=output, stderr=subprocess.PIPE)
if result.returncode:
    path.unlink()  # 仅删除本次未完成的备份，保留已有备份。
    raise SystemExit('备份失败，未继续迁移；请检查专用账号的备份权限。')
print(path)
