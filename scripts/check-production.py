#!/usr/bin/env python3
"""上线前只读检查；仅输出项目名和问题，不打印密钥、联系人或协议正文。"""
import argparse
import json
import os
import re
import sys
import urllib.error
import urllib.request
from urllib.parse import urlsplit


def configuration_errors(env):
    errors = []
    domain = env.get("PAW_DOMAIN", "").strip()
    valid_domain = bool(re.fullmatch(r"(?=.{1,253}$)(?:[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?\.)+[a-zA-Z]{2,63}", domain))
    if not valid_domain or domain.endswith((".invalid", ".test", ".localhost", ".example")) or domain in ("example.com", "example.org", "example.net"):
        errors.append("PAW_DOMAIN 必须是真实公网域名（不含协议、端口和路径）")
    if not re.fullmatch(r"[^\s@]+@[^\s@]+\.[^\s@]+", env.get("ACME_EMAIL", "")):
        errors.append("ACME_EMAIL 未填写有效邮箱")
    for key in ("MYSQL_ROOT_PASSWORD", "PAW_DB_PASSWORD", "PAW_REDIS_PASSWORD", "PAW_ADMIN_PASSWORD"):
        if len(env.get(key, "")) < 12:
            errors.append(key + " 至少需要 12 位；既有数据库和管理员密码须单独验证")
    for key in ("DEEPSEEK_API_KEY", "PAW_SMS_SIGN_NAME"):
        if not env.get(key, "").strip():
            errors.append(key + " 未配置")
    sms_pair = [env.get(k, "").strip() for k in ("PAW_SMS_ACCESS_KEY_ID", "PAW_SMS_ACCESS_KEY_SECRET")]
    oss_pair = [env.get(k, "").strip() for k in ("OSS_ACCESS_KEY_ID", "OSS_ACCESS_KEY_SECRET")]
    if not all(sms_pair if any(sms_pair) else oss_pair):
        errors.append("短信凭据必须成对配置，不可混用专用凭据与 OSS 凭据")
    for event in ("SUBMITTED", "CONFIRMED", "CANCELLED", "EXPIRED", "COMPLETED"):
        key = "PAW_SMS_APPOINTMENT_" + event + "_TEMPLATE"
        if not re.fullmatch(r"SMS_[A-Za-z0-9]+", env.get(key, "")):
            errors.append(key + " 未配置有效模板编号")
    if env.get("PAW_OSS_ENABLED", "false").lower() == "true":
        if not all(oss_pair) or not env.get("PAW_OSS_BUCKET", "").strip():
            errors.append("已启用 OSS，但凭据或 Bucket 不完整")
    # 微信是可选登录方式；只要填写了其中一项，就必须完成整套配置。
    wechat = [env.get(k, "").strip() for k in ("PAW_WECHAT_APP_ID", "PAW_WECHAT_APP_SECRET", "PAW_WECHAT_OAUTH_REDIRECT")]
    if any(wechat):
        callback = urlsplit(wechat[2])
        if not all(wechat) or callback.scheme != "https" or callback.netloc != domain or callback.path != "/login" or callback.query or callback.fragment:
            errors.append("微信配置不完整，回调须为本站 https://域名/login")
    return errors


def get_json(base, path):
    with urllib.request.urlopen(base + path, timeout=15) as response:
        return json.load(response)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--config-only", action="store_true")
    parser.add_argument("--public", action="store_true", help="通过真实公网 HTTPS 入口验收")
    args = parser.parse_args()
    errors = configuration_errors(os.environ)
    for error in errors:
        print("BLOCKED:", error)
    if not args.config_only:
        base = "https://" + os.environ.get("PAW_DOMAIN", "") if args.public else "http://web"
        # 域名输入不合法时不访问任意外部地址；公网检查始终使用系统证书校验。
        if args.public and any(e.startswith("PAW_DOMAIN") for e in errors):
            return 1
        for path, expected in (("/", 200), ("/admin/", 200), ("/api/v1/pets", 200), ("/api/v1/admin/pets", 401), ("/api/v1/orders", 401)):
            try:
                with urllib.request.urlopen(base + path, timeout=15) as response:
                    status = response.status
            except urllib.error.HTTPError as error:
                status = error.code
            except (OSError, ValueError):
                status = None
            ok = status == expected
            print("PASS:" if ok else "BLOCKED:", path, "HTTP", status)
            if not ok:
                errors.append("入口或权限校验失败")
        try:
            shop = get_json(base, "/api/v1/shop")["data"]
            placeholders = ("待配置", "待店主配置", "请联系门店确认", "演示", "示例", "TEST ONLY")
            for key in ("name", "address", "phone", "businessHours"):
                value = str(shop.get(key, ""))
                if not value or any(word in value for word in placeholders):
                    errors.append("门店资料 " + key + " 缺失或含占位内容")
                    print("BLOCKED:", errors[-1])
            agreement = get_json(base, "/api/v1/agreements/current?type=live_pet_trade")["data"]
            if not agreement or any(word in json.dumps(agreement, ensure_ascii=False) for word in ("TEST ONLY", "仅供测试", "演示协议")):
                errors.append("正式协议缺失或仍是测试内容")
                print("BLOCKED:", errors[-1])
        except (OSError, ValueError, KeyError, TypeError):
            errors.append("门店资料或正式协议无法读取")
            print("BLOCKED:", errors[-1])
    print("MANUAL: 店主确认门店/宠物/检疫/协议真实有效；短信审核及送达、备份恢复、真实预约交付须单独验收。")
    print("结果：存在运营阻挡项" if errors else "结果：自动检查通过，仍需完成人工及第三方验收")
    return 1 if errors else 0


if __name__ == "__main__":
    sys.exit(main())
