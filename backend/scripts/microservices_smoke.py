#!/usr/bin/env python3
"""三个独立 JVM + Nacos/Feign/Gateway 联调；仅使用临时 H2 和独立 Redis，不访问运行库。"""
import json
import os
from pathlib import Path
import runpy
import secrets
import socket
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.request
import uuid
import zipfile

ROOT = Path(__file__).resolve().parents[1]
WORK = Path(tempfile.mkdtemp(prefix='warmpaw-micro-test-'))
processes = []
logs = []
redis_name = 'warmpaw-test-' + uuid.uuid4().hex[:10]


def free_port():
    with socket.socket() as sock:
        sock.bind(('127.0.0.1', 0))
        return sock.getsockname()[1]


def start(name, command, env):
    log = (WORK / (name + '.log')).open('w')
    logs.append(log)
    process = subprocess.Popen(command, cwd=WORK, env=env, stdout=log, stderr=subprocess.STDOUT)
    processes.append(process)
    return process


def wait_http(port, process):
    for _ in range(120):
        if process.poll() is not None:
            raise RuntimeError(f'服务启动失败，请检查 {WORK} 下的日志')
        try:
            with urllib.request.urlopen(f'http://127.0.0.1:{port}/actuator/health', timeout=2) as res:
                if json.load(res)['status'] == 'UP':
                    return
        except (OSError, ValueError):
            pass
        time.sleep(0.5)
    raise RuntimeError(f'端口 {port} 的服务启动超时；日志目录 {WORK}')


def raw(base, path, token=None):
    headers = {} if token is None else {'Authorization': 'Bearer ' + token}
    request = urllib.request.Request(base + path, headers=headers)
    try:
        with urllib.request.urlopen(request, timeout=20) as response:
            return response.status, json.load(response)
    except urllib.error.HTTPError as error:
        return error.code, json.load(error)


def main():
    # 清除继承的业务配置，再显式指向本次隔离资源，避免触碰真实数据库与验证码。
    env = {k: v for k, v in os.environ.items()
           if not k.startswith(('PAW_', 'SPRING_', 'NACOS_', 'SERVER_', 'SERVICE_'))}
    db_port, redis_port, order_port, admin_port, gateway_port = [free_port() for _ in range(5)]
    group = 'WARMPAW_TEST_' + uuid.uuid4().hex[:12]
    password = secrets.token_urlsafe(24)
    data = WORK / 'data'
    data.mkdir()
    secret_file = data / 'local-admin-password.txt'
    secret_file.write_text(password)
    secret_file.chmod(0o600)
    env.update({
        'PAW_DB_URL': f'jdbc:h2:tcp://127.0.0.1:{db_port}/mem:micro;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=15000',
        'PAW_DB_USERNAME': 'sa', 'PAW_DB_PASSWORD': '',
        'PAW_REDIS_HOST': '127.0.0.1', 'PAW_REDIS_PORT': str(redis_port), 'PAW_REDIS_PASSWORD': '',
        'PAW_ADMIN_PASSWORD': password, 'PAW_STORAGE': str(data / 'files'),
        'SPRING_PROFILES_ACTIVE': 'local', 'SPRING_SQL_INIT_MODE': 'always',
        'NACOS_SERVER_ADDR': os.environ.get('NACOS_SERVER_ADDR', '127.0.0.1:8848'),
        'NACOS_GROUP': group, 'SERVER_ADDRESS': '127.0.0.1', 'SERVICE_IP': '127.0.0.1',
    })
    # 使用本次构建实际打包的 H2 驱动，不依赖另一个版本的本机数据库工具。
    with zipfile.ZipFile(ROOT / 'order-server/target/order-server-1.0.0-exec.jar') as jar:
        member = next(name for name in jar.namelist() if name.startswith('BOOT-INF/lib/h2-'))
        h2 = WORK / 'h2.jar'
        h2.write_bytes(jar.read(member))
    db = start('h2', ['java', '-Xmx128m', '-cp', str(h2), 'org.h2.tools.Server',
                     '-tcp', '-tcpPort', str(db_port), '-ifNotExists'], env)
    for _ in range(60):
        try:
            with socket.create_connection(('127.0.0.1', db_port), timeout=1):
                break
        except OSError:
            if db.poll() is not None:
                raise RuntimeError('隔离 H2 启动失败')
            time.sleep(0.2)
    subprocess.run(['docker', 'run', '--rm', '-d', '--name', redis_name,
                    '-p', f'127.0.0.1:{redis_port}:6379', 'redis:8.2-alpine'],
                   check=True, stdout=subprocess.DEVNULL)
    services = {}
    for name, port in [('order-server', order_port), ('admin-server', admin_port), ('gateway-server', gateway_port)]:
        process = start(name, ['java', '-Xms64m', '-Xmx256m', '-jar',
                        str(ROOT / name / 'target' / f'{name}-1.0.0-exec.jar')],
                        {**env, 'SERVER_PORT': str(port)})
        services[name] = process
        wait_http(port, process)
        print(f'{name} 独立启动通过，端口 {port}', flush=True)
    base = f'http://127.0.0.1:{gateway_port}/api/v1'
    # Gateway 必须经过 Nacos 发现服务，不能靠固定 URL 掩盖注册问题。
    for _ in range(60):
        if raw(base, '/home')[0] == 200:
            break
        time.sleep(0.5)
    else:
        raise RuntimeError('Gateway 未能通过 Nacos 发现管理服务')
    sys.argv = ['local_smoke.py', '--base', base, '--state-dir', str(data)]
    flow = runpy.run_path(str(ROOT / 'scripts/local_smoke.py'), run_name='__main__')
    admin, buyer, order = flow['admin'], flow['buyer'], flow['order']
    # 核查实际 Feign 的身份透传、查询参数和错误状态。
    assert raw(base, '/admin/orders')[0] == 401
    assert raw(base, '/admin/orders', buyer)[0] == 403
    assert raw(base, '/admin/orders/order_missing', admin)[0] == 404
    assert raw(base, '/admin/orders?orderNo=NO_SUCH_ORDER', admin)[1]['data']['total'] == 0
    assert raw(base, '/admin/orders/' + order['id'], admin)[1]['data']['status'] == 'completed'
    assert raw(base, '/admin/dashboard', admin)[0] == 200
    # 运行时证明职责边界：管理服务没有买家下单接口，订单服务没有商品写接口。
    assert raw(f'http://127.0.0.1:{admin_port}/api/v1', '/orders', buyer)[0] == 404
    assert raw(f'http://127.0.0.1:{order_port}/api/v1', '/admin/pets', admin)[0] == 404
    services['order-server'].terminate()
    services['order-server'].wait(timeout=30)
    assert raw(base, '/admin/orders', admin)[0] == 503
    assert raw(base, '/pets')[0] == 200
    print(json.dumps({'result': 'PASS', 'logs': str(WORK), 'verified': [
        'Nacos服务发现', 'Gateway路由', '跨服务登录与验证码', 'Feign后台订单与核销', '跨服务核销幂等重放',
        '参数和401/403/404透传', '订单服务停止返回503', '目录服务独立可用']}, ensure_ascii=False), flush=True)


try:
    main()
finally:
    # 仅停止本脚本创建的进程与临时 Redis；保留日志，不操作运行库和现有容器。
    for process in reversed(processes):
        if process.poll() is None:
            process.terminate()
            try:
                process.wait(timeout=15)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait()
    subprocess.run(['docker', 'stop', redis_name], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    for log in logs:
        log.close()
