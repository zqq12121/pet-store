#!/bin/sh
# 前台单 worker 运行；Ctrl+C 停止。多实例前需替换会话锁与任务调度。
set -eu
cd "$(dirname "$0")"
exec .venv/bin/python -m app
