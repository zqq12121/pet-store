"""环境配置集中读取；不在日志或 HTTP 响应中输出密钥。"""
import os
from dataclasses import dataclass, field
from pathlib import Path

from dotenv import load_dotenv

ROOT = Path(__file__).resolve().parents[1]
load_dotenv(ROOT / ".env")


def local_path(name: str, default: str) -> Path:
    path = Path(os.getenv(name, default))
    return path if path.is_absolute() else ROOT / path


@dataclass
class Settings:
    api_key: str = field(default_factory=lambda: os.getenv("DEEPSEEK_API_KEY", ""), repr=False)
    model: str = field(default_factory=lambda: os.getenv("DEEPSEEK_MODEL", "deepseek-chat"))
    api_base: str = field(default_factory=lambda: os.getenv("DEEPSEEK_API_BASE", "https://api.deepseek.com"))
    catalog_url: str = field(default_factory=lambda: os.getenv("PAW_CATALOG_URL", "http://127.0.0.1:8082"))
    order_url: str = field(default_factory=lambda: os.getenv("PAW_ORDER_URL", "http://127.0.0.1:8081"))
    db_path: Path = field(default_factory=lambda: local_path("AI_DB_PATH", "data/ai.sqlite3"))
    knowledge_path: Path = field(default_factory=lambda: local_path("AI_KNOWLEDGE_PATH", "knowledge/entries.json"))
    model_cache: Path = field(default_factory=lambda: local_path("AI_MODEL_CACHE", "data/models"))
    embedding_model: str = field(default_factory=lambda: os.getenv("AI_EMBEDDING_MODEL", "BAAI/bge-small-zh-v1.5"))
    generation_timeout: float = field(default_factory=lambda: float(os.getenv("AI_GENERATION_TIMEOUT", "30")))
