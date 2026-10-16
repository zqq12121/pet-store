#!/bin/sh
# 2 GiB 演示机顺序启动，避免多个 JVM 和模型同时冷启动争抢磁盘与交换空间。
set -eu
cd "$(dirname "$0")/.."
compose() {
  docker compose -f compose.yaml -f compose.cloud-demo.yaml "$@"
}
compose up -d --no-build --wait --wait-timeout 900 mysql redis nacos
for service in admin-server order-server gateway-server ai-service web; do
  # 已耗尽健康探测次数的应用需先重启，否则 --wait 会立即返回失败。
  if [ "$(compose ps --format '{{.Health}}' "$service")" = unhealthy ]; then
    compose restart "$service"
  fi
  # 前一项已通过健康检查；不让 Compose 再并行重启其依赖。
  compose up -d --no-deps --no-build --wait --wait-timeout 900 "$service"
done
# 网关进程健康后，Nacos 实例传播仍可能短暂滞后；确认真实业务转发也已就绪。
tries=0
until compose exec -T gateway-server curl -fsS http://localhost:8080/api/v1/home >/dev/null 2>&1; do
  tries=$((tries + 1))
  if [ "$tries" -ge 60 ]; then
    printf '%s\n' '网关尚未发现可用业务实例，请检查 Nacos 注册状态。' >&2
    exit 1
  fi
  sleep 5
done
compose ps
