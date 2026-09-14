FROM ghcr.io/astral-sh/uv:0.8.22 AS uv
FROM python:3.11-slim
COPY --from=uv /uv /usr/local/bin/uv
WORKDIR /app
COPY pyproject.toml uv.lock ./
# 使用锁文件安装运行依赖，不携带测试工具和宿主机虚拟环境。
RUN uv sync --locked --no-dev --no-install-project
COPY app ./app
COPY knowledge ./knowledge
RUN useradd --uid 10001 --create-home app && mkdir -p /app/data && chown -R app:app /app
USER app
ENV PATH="/app/.venv/bin:$PATH" PYTHONUNBUFFERED=1 AI_HOST=0.0.0.0
CMD ["python", "-m", "app"]
