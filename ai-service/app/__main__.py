"""读取 .env 后启动，端口与数据路径采用同一套环境配置。"""
import os

import uvicorn

from .config import Settings

if __name__ == "__main__":
    Settings()
    uvicorn.run("app.main:create_app", factory=True,
                host=os.getenv("AI_HOST", "127.0.0.1"), port=int(os.getenv("AI_PORT", "8083")), workers=1)
