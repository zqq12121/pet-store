"""真实进程验证启停和保护边界；使用临时数据库，不访问模型或业务服务。"""
import os
from pathlib import Path
import socket
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]


def environment(tmp_path, port):
    seed = tmp_path / "entries.json"
    seed.write_text("[]")
    return {**os.environ, "AI_DB_PATH": str(tmp_path / "ai.sqlite3"),
            "AI_KNOWLEDGE_PATH": str(seed), "AI_HOST": "127.0.0.1", "AI_PORT": str(port),
            "DEEPSEEK_API_KEY": ""}


def run(action, env):
    return subprocess.run([sys.executable, str(ROOT / "manage.py"), action],
                          env=env, capture_output=True, text=True, timeout=50)


def test_lifecycle_preserves_data_and_rejects_duplicate_start(tmp_path):
    with socket.socket() as listener:
        listener.bind(("127.0.0.1", 0))
        port = listener.getsockname()[1]
    env = environment(tmp_path, port)
    try:
        result = run("start", env)
        assert result.returncode == 0, result.stdout + result.stderr
        pid = (tmp_path / "run/ai-service.pid").read_text()
        assert run("status", env).returncode == 0
        assert run("start", env).returncode != 0
        assert (tmp_path / "run/ai-service.pid").read_text() == pid
    finally:
        result = run("stop", env)
        assert result.returncode == 0, result.stdout + result.stderr
    assert (tmp_path / "ai.sqlite3").exists()
    assert not (tmp_path / "run/ai-service.pid").exists()
    assert run("status", env).returncode != 0
    assert run("stop", env).returncode == 0


def test_port_conflict_and_reused_pid_do_not_kill_other_processes(tmp_path):
    with socket.socket() as listener:
        listener.bind(("127.0.0.1", 0))
        listener.listen()
        env = environment(tmp_path, listener.getsockname()[1])
        result = run("start", env)
        assert result.returncode != 0
        assert "已被占用" in result.stderr
        assert not (tmp_path / "ai.sqlite3").exists()
    # 模拟 PID 复用：当前测试进程必须保留，且不删除有冲突的记录。
    pid_file = tmp_path / "run/ai-service.pid"
    pid_file.write_text(str(os.getpid()))
    result = run("stop", env)
    assert result.returncode != 0
    assert "其他进程" in result.stderr
    assert pid_file.read_text() == str(os.getpid())
