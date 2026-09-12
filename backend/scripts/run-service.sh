#!/bin/sh
# 所有 Java 服务从 backend 启动，复用现有密钥、上传目录与本地验证码收件箱。
set -eu
cd "$(dirname "$0")/.."
case "${1:-}" in
  order-server|admin-server|gateway-server) service="$1" ;;
  *) echo '用法：sh scripts/run-service.sh order-server|admin-server|gateway-server' >&2; exit 1 ;;
esac
if [ -f .env ]; then
  set -a
  . ./.env
  set +a
fi
export SPRING_PROFILES_ACTIVE="${SPRING_PROFILES_ACTIVE:-local}"
exec java -Xms128m -Xmx384m -jar "$service/target/$service-1.0.0-exec.jar"
