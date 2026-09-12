#!/bin/sh
# 只停止启动脚本记录的 Java 服务；Nacos、MySQL、Redis 和数据卷保持运行。
set -eu
cd "$(dirname "$0")/.."
for service in gateway-server admin-server order-server; do
  file="data/run/$service.pid"
  test -f "$file" || continue
  pid=$(cat "$file")
  case "$pid" in ''|*[!0-9]*) echo "忽略无效 PID 文件：$file" >&2; continue ;; esac
  command=$(ps -p "$pid" -o command= 2>/dev/null || true)
  case "$command" in
    *"$service-1.0.0-exec.jar"*) kill "$pid"; echo "已停止 $service" ;;
    '') ;;
    *) echo "PID $pid 已属于其他进程，未停止。" >&2; continue ;;
  esac
  rm "$file"
done
