#!/bin/sh
# 兼容原启动命令：项目已拆分为独立服务，统一由微服务启动脚本管理。
set -eu
cd "$(dirname "$0")/.."
exec sh scripts/start-microservices.sh
