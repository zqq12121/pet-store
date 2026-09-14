#!/usr/bin/env python3
"""将完整备份恢复到全新 Compose 项目；不覆盖已有卷，不启动可能发送通知的业务服务。"""
import argparse
import json
from pathlib import Path
import re
import subprocess
import tarfile
import tempfile

ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("backup", type=Path)
    parser.add_argument("--project", required=True)
    args = parser.parse_args()
    if not re.fullmatch(r"warmpaw-restore-[a-z0-9-]+", args.project):
        parser.error("恢复项目必须使用 warmpaw-restore- 前缀，避免覆盖运行项目")
    folder = args.backup.resolve()
    manifest = json.loads((folder / "manifest.json").read_text())
    if manifest.get("complete") is not True or manifest.get("format") != 1:
        raise SystemExit("不是完整的受支持备份")
    for name in ("mysql.sql", "uploads", "ai-data"):
        if not (folder / name).exists():
            raise SystemExit("备份缺少：" + name)
    volumes = subprocess.run(["docker", "volume", "ls", "--format", "{{.Name}}"], capture_output=True, text=True, check=True).stdout.split()
    if any(name.startswith(args.project + "_") for name in volumes):
        raise SystemExit("目标已有数据卷，未覆盖；请选择全新恢复项目名")
    compose = ["docker", "compose", "--project-directory", str(ROOT), "-p", args.project]
    subprocess.run([*compose, "up", "-d", "--wait", "--wait-timeout", "180", "mysql", "redis"], check=True)
    with (folder / "mysql.sql").open("rb") as source:
        subprocess.run([*compose, "exec", "-T", "mysql", "sh", "-c",
            'MYSQL_PWD="$MYSQL_PASSWORD" exec mysql -u"$MYSQL_USER" "$MYSQL_DATABASE"'], stdin=source, check=True)
    for name, source in (("uploads", folder / "uploads"), ("ai-data", folder / "ai-data")):
        volume = args.project + "_" + name
        subprocess.run(["docker", "volume", "create", "--label", "com.docker.compose.project=" + args.project,
            "--label", "com.docker.compose.volume=" + name, volume], check=True, stdout=subprocess.DEVNULL)
        # 由已知备份目录生成归档，不接受外部压缩包中的任意目标路径。
        with tempfile.TemporaryFile() as data:
            with tarfile.open(fileobj=data, mode="w") as archive:
                archive.add(source, arcname=".")
            data.seek(0)
            subprocess.run(["docker", "run", "--rm", "-i", "--mount", f"type=volume,src={volume},dst=/restore",
                "redis:8.2-alpine", "sh", "-c", "tar -xf - -C /restore && chown -R 10001:10001 /restore"],
                stdin=data, check=True)
    print("恢复完成：", args.project, "。业务服务未启动；请先校验数据，再按交付说明切换。")


if __name__ == "__main__":
    main()
