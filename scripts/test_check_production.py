"""配置检查只使用虚构输入，不读取本机 .env 或调用外部服务。"""
import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location("production", Path(__file__).with_name("check-production.py"))
production = importlib.util.module_from_spec(spec)
spec.loader.exec_module(production)


class ProductionCheckTest(unittest.TestCase):
    def fixture(self):
        env = {k: "test-only-password" for k in ("MYSQL_ROOT_PASSWORD", "PAW_DB_PASSWORD", "PAW_REDIS_PASSWORD", "PAW_ADMIN_PASSWORD")}
        env.update(PAW_DOMAIN="shop.warmpaw.cn", ACME_EMAIL="ops@warmpaw.cn", DEEPSEEK_API_KEY="test-only",
                   PAW_SMS_SIGN_NAME="测试", OSS_ACCESS_KEY_ID="test-id", OSS_ACCESS_KEY_SECRET="test-secret")
        env.update({"PAW_SMS_APPOINTMENT_" + event + "_TEMPLATE": "SMS_TEST" for event in ("SUBMITTED", "CONFIRMED", "CANCELLED", "EXPIRED", "COMPLETED")})
        return env

    def test_complete_sms_configuration_does_not_require_optional_wechat(self):
        self.assertEqual([], production.configuration_errors(self.fixture()))

    def test_missing_templates_block_readiness(self):
        env = self.fixture()
        del env["PAW_SMS_APPOINTMENT_COMPLETED_TEMPLATE"]
        self.assertTrue(any("COMPLETED_TEMPLATE" in e for e in production.configuration_errors(env)))

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


if __name__ == "__main__":
    unittest.main()
