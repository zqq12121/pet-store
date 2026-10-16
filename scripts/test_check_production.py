"""配置检查只使用虚构输入，不读取本机 .env 或调用外部服务。"""
import importlib.util
import contextlib
import io
from pathlib import Path
import unittest
from unittest.mock import MagicMock, patch

spec = importlib.util.spec_from_file_location("production", Path(__file__).with_name("check-production.py"))
production = importlib.util.module_from_spec(spec)
spec.loader.exec_module(production)


class ProductionCheckTest(unittest.TestCase):
    def fixture(self):
        env = {k: "test-only-password" for k in ("MYSQL_ROOT_PASSWORD", "PAW_DB_PASSWORD", "PAW_REDIS_PASSWORD", "PAW_ADMIN_PASSWORD")}
        env.update(PAW_DOMAIN="shop.warmpaw.cn", ACME_EMAIL="ops@warmpaw.cn", DEEPSEEK_API_KEY="test-only",
                   PAW_CAPTCHA_APP_ID="test-captcha", PAW_CAPTCHA_APP_KEY="test-key",
                   PAW_SMS_SIGN_NAME="测试", OSS_ACCESS_KEY_ID="test-id", OSS_ACCESS_KEY_SECRET="test-secret")
        env.update({"PAW_SMS_APPOINTMENT_" + event + "_TEMPLATE": "SMS_TEST" for event in ("SUBMITTED", "CONFIRMED", "CANCELLED", "EXPIRED", "COMPLETED")})
        return env

    def test_complete_sms_configuration_does_not_require_optional_wechat(self):
        self.assertEqual([], production.configuration_errors(self.fixture()))

    def test_missing_templates_block_readiness(self):
        env = self.fixture()
        del env["PAW_SMS_APPOINTMENT_COMPLETED_TEMPLATE"]
        self.assertTrue(any("COMPLETED_TEMPLATE" in e for e in production.configuration_errors(env)))

    def test_missing_captcha_or_partial_wechat_cannot_pass_readiness(self):
        env = self.fixture()
        del env["PAW_CAPTCHA_APP_KEY"]
        self.assertTrue(any("PAW_CAPTCHA_APP_KEY" in e for e in production.configuration_errors(env)))
        env = self.fixture()
        env["PAW_WECHAT_WEB_APP_ID"] = "partial"
        self.assertTrue(any("网站应用微信" in e for e in production.configuration_errors(env)))
        env = self.fixture()
        env["PAW_WECHAT_MINI_APP_ID"] = "wx0123456789abcdef"
        self.assertTrue(any("小程序扫码" in e for e in production.configuration_errors(env)))
        env["PAW_WECHAT_MINI_APP_SECRET"] = "test-secret"
        env["PAW_WECHAT_MINI_ENV_VERSION"] = "develop"
        self.assertTrue(any("release" in e for e in production.configuration_errors(env)))

    def test_partial_dedicated_credentials_do_not_fall_back(self):
        env = self.fixture()
        env["PAW_SMS_ACCESS_KEY_ID"] = "partial-id"
        self.assertTrue(any("成对配置" in e for e in production.configuration_errors(env)))

    def test_rejects_urls_and_non_public_domain_placeholders(self):
        for domain in ("", "localhost", "127.0.0.1", "example.com", "shop.invalid", "https://shop.warmpaw.cn", "shop.warmpaw.cn/login"):
            env = self.fixture()
            env["PAW_DOMAIN"] = domain
            self.assertTrue(any(e.startswith("PAW_DOMAIN") for e in production.configuration_errors(env)))

    def test_wechat_callback_must_return_to_same_site_login(self):
        env = self.fixture()
        env.update(PAW_WECHAT_APP_ID="test-id", PAW_WECHAT_APP_SECRET="test-secret", PAW_WECHAT_OAUTH_REDIRECT="https://foreign.invalid/login")
        self.assertTrue(any("微信" in e for e in production.configuration_errors(env)))
        env["PAW_WECHAT_OAUTH_REDIRECT"] = "https://shop.warmpaw.cn/login"
        self.assertEqual([], production.configuration_errors(env))

    def test_mini_program_requires_own_credentials_but_not_sms_or_ai(self):
        env = {k: "test-only-password" for k in ("MYSQL_ROOT_PASSWORD", "PAW_DB_PASSWORD", "PAW_REDIS_PASSWORD", "PAW_ADMIN_PASSWORD")}
        env.update(PAW_DOMAIN="shop.warmpaw.cn", ACME_EMAIL="ops@warmpaw.cn",
                   PAW_WECHAT_MINI_APP_ID="wx0123456789abcdef", PAW_WECHAT_MINI_APP_SECRET="test-only-secret")
        self.assertEqual([], production.configuration_errors(env, mini_program=True))
        del env["PAW_WECHAT_MINI_APP_SECRET"]
        self.assertTrue(any("PAW_WECHAT_MINI_APP_SECRET" in e for e in production.configuration_errors(env, mini_program=True)))
        env["PAW_WECHAT_MINI_APP_ID"] = "website-app-id"
        self.assertTrue(any("PAW_WECHAT_MINI_APP_ID" in e for e in production.configuration_errors(env, mini_program=True)))

    def test_mini_program_probes_only_its_public_routes_and_permissions(self):
        env = self.fixture()
        env.update(PAW_WECHAT_MINI_APP_ID="wx0123456789abcdef", PAW_WECHAT_MINI_APP_SECRET="test-only-secret")
        visited = []

        def respond(url, timeout):
            visited.append(url)
            if url.endswith(("/me", "/me/favorites", "/admin/pets")):
                raise production.urllib.error.HTTPError(url, 401, "Unauthorized", {}, None)
            response = MagicMock()
            response.__enter__.return_value.status = 200
            return response

        # 用模拟 HTTP 验证 CLI 分支，防止把网站协议或短信检查混进小程序验收。
        with patch.dict(production.os.environ, env, clear=True), patch.object(production.sys, "argv", ["check-production.py", "--mini-program"]), patch.object(production.urllib.request, "urlopen", side_effect=respond), contextlib.redirect_stdout(io.StringIO()):
            self.assertEqual(0, production.main())
        self.assertEqual(["http://web/api/v1" + p for p in ("/home", "/pets", "/me", "/me/favorites", "/admin/pets")], visited)


if __name__ == "__main__":
    unittest.main()
