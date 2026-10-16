#!/usr/bin/env python3
"""停写后备份 Compose 业务库、上传文件与 AI 数据；不输出数据库密码。"""
from datetime import datetime
import argparse
import json
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parents[1]
COMPOSE = ["docker", "compose", "--project-directory", str(ROOT)]
WRITERS = ["web", "gateway-server", "admin-server", "order-server", "ai-service"]


def run(*args, **kwargs):
    return subprocess.run([*COMPOSE, *args], check=True, **kwargs)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--leave-stopped", action="store_true", help="完整备份后保持写入应用停止，供低内存机器直接升级")
    args = parser.parse_args()
    folder = ROOT / "backups" / datetime.now().strftime("%Y%m%d-%H%M%S")
    folder.mkdir(parents=True, mode=0o700)
    running = run("ps", "--services", "--status", "running", capture_output=True, text=True).stdout.split()
    resume = [name for name in WRITERS if name in running]
    complete = False
    try:
        if resume:
            run("stop", "--timeout", "60", *resume)
        # mysqldump 在数据库容器内读取已有环境，不将密码放入主机参数。
        with (folder / "mysql.sql").open("xb") as output:
            run("exec", "-T", "mysql", "sh", "-c",
                'MYSQL_PWD="$MYSQL_PASSWORD" exec mysqldump -u"$MYSQL_USER" --single-transaction --no-tablespaces --set-gtid-purged=OFF --hex-blob "$MYSQL_DATABASE"',
                stdout=output)
        run("cp", "admin-server:/app/data/files", str(folder / "uploads"))
        run("cp", "ai-service:/app/data", str(folder / "ai-data"))
        # 仅完整备份产生完成标记；恢复前必须检查它。
        (folder / "manifest.json").write_text(json.dumps({"complete": True, "format": 1,
            "contents": ["mysql.sql", "uploads", "ai-data"],
            "redis": "不恢复临时验证码和限流状态；恢复后重新登录"}, ensure_ascii=False, indent=2))
        complete = True
        print("完整备份：", folder)
        if args.leave_stopped:
            print("写入应用保持停止；更新后按部署配置恢复服务。")
    finally:
        # 只重启原本正在运行的应用，不额外启用用户已停止的服务。
        # 备份失败必须恢复原应用；只有完整成功且显式指定时才保持维护停机。
        if resume and (not args.leave_stopped or not complete):
            run("start", *resume)


if __name__ == "__main__":
    main()
