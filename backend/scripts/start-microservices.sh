#!/bin/sh
# 启动注册中心和三个独立进程；端口冲突时明确退出，不终止其他程序。
set -eu
cd "$(dirname "$0")/.."
for port in 8080 8081 8082; do
  if lsof -nP -iTCP:"$port" -sTCP:LISTEN >/dev/null 2>&1; then
    echo "端口 $port 已被占用，请先停止对应旧后端或已有微服务。" >&2
    exit 1
  fi
done
for service in order-server admin-server gateway-server; do
  test -f "$service/target/$service-1.0.0-exec.jar" || {
    echo '请先在 backend 执行 mvn package。' >&2; exit 1;
  }
done
python3 scripts/init-nacos-env.py
docker compose -p warmpaw-micro -f deploy/compose-microservices.yml up -d
# 等注册中心就绪后再启动客户端，首次拉起容器也可使用同一条命令。
attempts=0
until curl -fsS "http://127.0.0.1:8848/nacos/v3/admin/core/state" >/dev/null 2>&1; do
  attempts=$((attempts + 1))
  if [ "$attempts" -ge 60 ]; then
    echo 'Nacos 启动超时，请检查注册中心容器日志。' >&2
    exit 1
  fi
  sleep 2
done
mkdir -p data/run data/logs
started=""
cleanup() {
  # 仅清理本次启动失败的子进程，不操作外部进程或数据库。
  for pid in $started; do kill "$pid" 2>/dev/null || true; done
}
trap cleanup HUP INT TERM
for service in order-server admin-server gateway-server; do
  # 独立进程会话防止终端或调用工具退出时连带终止服务，日志不绑定父进程管道。
  pid=$(python3 - "$service" <<'PY'
import subprocess
import sys
service = sys.argv[1]
with open(f'data/logs/{service}.log', 'w') as log:
    process = subprocess.Popen(['sh', 'scripts/run-service.sh', service],
                               stdin=subprocess.DEVNULL, stdout=log, stderr=subprocess.STDOUT,
                               start_new_session=True)
print(process.pid)
PY
  )
  started="$started $pid"
  echo "$pid" >"data/run/$service.pid"
done
for port in 8081 8082 8080; do
  attempts=0
  until curl -fsS "http://127.0.0.1:$port/actuator/health" >/dev/null 2>&1; do
    attempts=$((attempts + 1))
    if [ "$attempts" -ge 60 ]; then
      cleanup
      echo "端口 $port 启动失败，请检查 backend/data/logs 下的日志。" >&2
      exit 1
    fi
    sleep 2
  done
done
echo '微服务已启动：Gateway 8080，order-server 8081，admin-server 8082；Nacos 控制台 http://localhost:8088/'
