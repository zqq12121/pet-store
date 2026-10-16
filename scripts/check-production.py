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


def configuration_errors(env, mini_program=False):
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
    # 小程序只依赖浏览、微信登录和收藏，不要求短信、支付或 AI 配置。
    if mini_program:
        if not re.fullmatch(r"wx[0-9a-fA-F]{16}", env.get("PAW_WECHAT_MINI_APP_ID", "")):
            errors.append("PAW_WECHAT_MINI_APP_ID 未配置有效小程序 AppID")
        if not env.get("PAW_WECHAT_MINI_APP_SECRET", "").strip():
            errors.append("PAW_WECHAT_MINI_APP_SECRET 未配置；只能保存在服务端")
        if env.get("PAW_WECHAT_MINI_ENV_VERSION", "release") != "release":
            errors.append("PAW_WECHAT_MINI_ENV_VERSION 正式上线须为 release")
        return errors
    for key in ("PAW_CAPTCHA_APP_ID", "PAW_CAPTCHA_APP_KEY"):
        if not env.get(key, "").strip():
            errors.append(key + " 未配置；买家登录必须通过图形认证")
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
    # 可选网站应用与小程序接入也要检查完整性，不能用另一类应用凭据代替。
    web = [env.get(k, "").strip() for k in ("PAW_WECHAT_WEB_APP_ID", "PAW_WECHAT_WEB_APP_SECRET", "PAW_WECHAT_WEB_REDIRECT")]
    if any(web):
        callback = urlsplit(web[2])
        if not all(web) or callback.scheme != "https" or callback.netloc != domain or callback.path != "/login" or callback.query or callback.fragment:
            errors.append("网站应用微信配置不完整，回调须为本站 https://域名/login")
    mini = [env.get(k, "").strip() for k in ("PAW_WECHAT_MINI_APP_ID", "PAW_WECHAT_MINI_APP_SECRET")]
    if any(mini):
        if not all(mini) or not re.fullmatch(r"wx[0-9a-fA-F]{16}", mini[0]):
            errors.append("小程序扫码登录凭据不完整或 AppID 无效")
        if env.get("PAW_WECHAT_MINI_ENV_VERSION", "release") != "release":
            errors.append("PAW_WECHAT_MINI_ENV_VERSION 正式上线须为 release")
    return errors


def get_json(base, path):
    with urllib.request.urlopen(base + path, timeout=15) as response:
        return json.load(response)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--config-only", action="store_true")
    parser.add_argument("--public", action="store_true", help="通过真实公网 HTTPS 入口验收")
    parser.add_argument("--mini-program", action="store_true", help="仅检查小程序所需配置及公开接口")
    args = parser.parse_args()
    errors = configuration_errors(os.environ, args.mini_program)
    for error in errors:
        print("BLOCKED:", error)
    if not args.config_only:
        base = "https://" + os.environ.get("PAW_DOMAIN", "") if args.public else "http://web"
        # 域名输入不合法时不访问任意外部地址；公网检查始终使用系统证书校验。
        if args.public and any(e.startswith("PAW_DOMAIN") for e in errors):
            return 1
        endpoints = (("/", 200), ("/admin/", 200), ("/api/v1/pets", 200), ("/api/v1/admin/pets", 401), ("/api/v1/orders", 401))
        if args.mini_program:
            endpoints = (("/api/v1/home", 200), ("/api/v1/pets", 200), ("/api/v1/me", 401), ("/api/v1/me/favorites", 401), ("/api/v1/admin/pets", 401))
        for path, expected in endpoints:
            try:
                with urllib.request.urlopen(base + path, timeout=15) as response:
                    status = response.status
            except urllib.error.HTTPError as error:
                status = error.code
                error.close()
            except (OSError, ValueError):
                status = None
            ok = status == expected
            print("PASS:" if ok else "BLOCKED:", path, "HTTP", status)
            if not ok:
                errors.append("入口或权限校验失败")
        if args.mini_program:
            # GET 不能证明真实微信换码或账号收藏写入，明确保留人工验收项。
            print("MANUAL: 核对收藏表迁移、图片 HTTPS、微信后台合法域名和备案；真机微信登录后验证收藏及跨设备同步。")
            print("结果：存在小程序上线阻挡项" if errors else "结果：小程序配置及只读接口检查通过，仍需平台与真机验收")
            return 1 if errors else 0
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
    print("MANUAL: 微信平台配置、备案、真机登录和跨设备收藏须单独验收。" if args.mini_program else "MANUAL: 店主确认门店/宠物/检疫/协议真实有效；短信审核及送达、备份恢复、真实预约交付须单独验收。")
    print("结果：存在运营阻挡项" if errors else "结果：自动检查通过，仍需完成人工及第三方验收")
    return 1 if errors else 0


if __name__ == "__main__":
    sys.exit(main())
