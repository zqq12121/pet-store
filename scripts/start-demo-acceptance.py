#!/usr/bin/env python3
"""从完整备份启动独立演示副本；不改变主项目配置、账户或数据卷。"""
import argparse
from datetime import datetime
import json
import os
from pathlib import Path
import socket
import subprocess

ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('backup', type=Path)
    parser.add_argument('--port', type=int, default=8190)
    args = parser.parse_args()
    # 演示只暴露本机入口，已占用的端口直接报错，不停止其他服务。
    with socket.socket() as probe:
        probe.bind(('127.0.0.1', args.port))
    project = 'warmpaw-restore-demo-' + datetime.now().strftime('%Y%m%d-%H%M%S')
    folder = ROOT / 'output' / project
    folder.mkdir(parents=True)
    env = {**os.environ, 'WEB_PORT': str(args.port)}
    # 辅助入口只来自 test-classes，不加入生产 JAR。
    with (folder / 'compile.log').open('w') as log:
        subprocess.run(['mvn', '-o', '-f', str(ROOT / 'backend/pom.xml'), '-pl',
                        'integration-tests', '-am', '-DskipTests', 'test-compile'],
                       cwd=ROOT, env=env, stdout=log, stderr=subprocess.STDOUT, check=True)
    jar = folder / 'demo-support.jar'
    subprocess.run(['jar', 'cf', str(jar), '-C',
                    str(ROOT / 'backend/integration-tests/target/test-classes'), 'demoacceptance'], check=True)
    subprocess.run(['python3', str(ROOT / 'scripts/restore-compose.py'), str(args.backup.resolve()),
                    '--project', project], cwd=ROOT, env=env, check=True)
    # 模拟环境删除第三方凭据；所有业务数据和服务发现使用本次 Compose 网络与卷。
    isolated = {'SPRING_PROFILES_ACTIVE': 'local', 'PAW_MOCK_PROVIDERS': 'true', 'PAW_OSS_ENABLED': 'false',
                **{key: '' for key in ('OSS_ACCESS_KEY_ID', 'OSS_ACCESS_KEY_SECRET',
                                      'PAW_SMS_ACCESS_KEY_ID', 'PAW_SMS_ACCESS_KEY_SECRET',
                                      'PAW_CAPTCHA_APP_ID', 'PAW_CAPTCHA_APP_KEY',
                                      'PAW_WECHAT_APP_ID', 'PAW_WECHAT_APP_SECRET',
                                      'PAW_WECHAT_OAUTH_REDIRECT')}}
    services = {name: {'image': 'warmpaw-' + name + ':latest'}
                for name in ('admin-server', 'order-server', 'gateway-server', 'ai-service', 'web')}
    services['order-server']['environment'] = isolated
    services['admin-server'].update(
        environment={**isolated, 'PAW_DEMO_ACCEPTANCE': 'true'},
        entrypoint=['java', '-Dloader.path=/app/demo-support.jar',
                    '-Dloader.main=demoacceptance.DemoAdminApplication', '-cp', '/app/app.jar',
                    'org.springframework.boot.loader.launch.PropertiesLauncher'],
        volumes=[str(jar) + ':/app/demo-support.jar:ro'])
    services['ai-service']['environment'] = {'DEEPSEEK_API_KEY': ''}
    override = folder / 'compose.json'
    override.write_text(json.dumps({'services': services}, indent=2))
    compose = ['docker', 'compose', '--project-directory', str(ROOT), '-p', project,
               '-f', str(ROOT / 'compose.yaml'), '-f', str(override)]
    subprocess.run([*compose, 'up', '-d', '--no-build', '--wait', '--wait-timeout', '600'],
                   cwd=ROOT, env=env, check=True)
    result = {'project': project, 'port': args.port, 'url': f'http://127.0.0.1:{args.port}',
              'compose': compose, 'backup': str(args.backup.resolve()), 'thirdPartyMode': 'simulated'}
    (folder / 'environment.json').write_text(json.dumps(result, ensure_ascii=False, indent=2))
    print(json.dumps(result, ensure_ascii=False))


if __name__ == '__main__':
    main()
