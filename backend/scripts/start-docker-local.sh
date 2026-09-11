#!/bin/sh
# 从 backend 启动，保持本地验证码收件箱及文件路径稳定。
set -eu
cd "$(dirname "$0")/.."
set -a
. ./.env
set +a
export SPRING_PROFILES_ACTIVE=local,docker
# Docker Desktop 的 Nginx 通过 host.docker.internal 访问宿主机后端。
export SERVER_ADDRESS=0.0.0.0
exec java -jar target/warmpaw-backend-1.0.0.jar
