"""管理当前 checkout 的单 worker 后台服务，不终止外部端口占用者。"""
import argparse
import fcntl
import json
import os
import signal
import socket
import subprocess
import time
from urllib.error import URLError
from urllib.request import ProxyHandler, build_opener

from app.config import ROOT, Settings


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=["start", "stop", "status"])
    args = parser.parse_args()
    settings = Settings()
    runtime = settings.db_path.parent / "run"
    runtime.mkdir(parents=True, exist_ok=True)
    # 同一数据目录的启停互斥，避免两个启动命令覆盖 PID 文件。
    lock = (runtime / "ai-service.lock").open("a")
    try:
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
    except BlockingIOError:
        raise SystemExit("另一个 AI 管理命令正在执行，请稍后重试")
    pid_file = runtime / "ai-service.pid"
    log_file = settings.db_path.parent / "logs" / "ai-service.log"
    command = [str(ROOT / ".venv/bin/python"), "-m", "app"]
    host = os.getenv("AI_HOST", "127.0.0.1")
    port = int(os.getenv("AI_PORT", "8083"))
    check_host = {"0.0.0.0": "127.0.0.1", "::": "::1"}.get(host, host)
    url_host = f"[{check_host}]" if ":" in check_host else check_host
    health_url = f"http://{url_host}:{port}/health"

    def owned_pid():
        if not pid_file.exists():
            return None
        value = pid_file.read_text().strip()
        if not value.isdigit() or int(value) <= 1:
            raise SystemExit(f"PID 文件无效，请检查：{pid_file}")
        pid = int(value)
        result = subprocess.run(["ps", "-p", str(pid), "-o", "stat=", "-o", "command="],
                                capture_output=True, text=True, check=False)
        if result.returncode and result.stderr.strip():
            raise SystemExit("无法读取进程状态，未操作服务或 PID 文件")
        if not result.stdout.strip():
            return None
        state, actual_command = result.stdout.strip().split(maxsplit=1)
        # 已退出的进程可能短暂等待系统回收，不再占端口，也无需再次发送信号。
        if state.startswith("Z"):
            return None
        # PID 可能被复用；必须匹配本项目虚拟环境及完整启动参数。
        if actual_command != " ".join(command):
            raise SystemExit(f"PID {pid} 已属于其他进程，未操作；请检查 {pid_file}")
        return pid

    def health():
        # 本机健康检查不走用户代理，避免把运行状态发到代理服务。
        try:
            with build_opener(ProxyHandler({})).open(health_url, timeout=1) as response:
                data = json.load(response)
                return data if data.get("status") == "UP" else None
        except (OSError, URLError, ValueError):
            return None

    pid = owned_pid()
    if args.action == "status":
        state = health() if pid else None
        if not state:
            raise SystemExit("AI 后台服务未运行或健康检查失败")
        print(f"AI 运行中：PID {pid}，{health_url}，模型密钥已配置={state.get('modelConfigured', False)}")
        return
    if args.action == "stop":
        if pid:
            os.kill(pid, signal.SIGTERM)
            for _ in range(100):
                if not owned_pid():
                    break
                time.sleep(0.1)
            else:
                raise SystemExit(f"AI 尚未退出，保留 PID 文件，请检查日志：{log_file}")
        pid_file.unlink(missing_ok=True)
        print("AI 后台服务已停止；数据库、知识向量与模型缓存保留")
        return
    if pid:
        raise SystemExit("AI 后台服务已运行；请先执行 status 或 stop")
    # 启动前检查端口；冲突时不接管已有的服务。
    try:
        with socket.create_connection((check_host, port), timeout=1):
            raise SystemExit(f"端口 {port} 已被占用，未启动 AI")
    except ConnectionRefusedError:
        pass
    runtime.mkdir(parents=True, exist_ok=True)
    log_file.parent.mkdir(parents=True, exist_ok=True)
    with log_file.open("a") as log:
        process = subprocess.Popen(command, cwd=ROOT, stdin=subprocess.DEVNULL,
                                   stdout=log, stderr=subprocess.STDOUT, start_new_session=True)
    try:
        pid_file.write_text(str(process.pid))
        for _ in range(150):
            if process.poll() is not None:
                raise RuntimeError("AI 进程已退出")
            if health() and process.poll() is None:
                print(f"AI 已启动：{health_url}；日志：{log_file}")
                return
            time.sleep(0.2)
        raise RuntimeError("AI 启动超时")
    except BaseException:
        # 失败只回收本次创建的子进程，不触碰已有业务服务。
        if process.poll() is None:
            process.terminate()
            try:
                process.wait(timeout=10)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait()
        pid_file.unlink(missing_ok=True)
        print(f"启动失败，请检查日志：{log_file}")
        raise


if __name__ == "__main__":
    main()
