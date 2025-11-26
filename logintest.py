"""
NBU 统一身份认证登录测试
"""

import os
import re
import os
import random
import os
import requests
from Crypto.Cipher import AES
from Crypto.Util.Padding import pad
import os
import base64

USERNAME = os.environ.get("NBU_USERNAME")
PASSWORD = os.environ.get("NBU_PASSWORD")

if not USERNAME or not PASSWORD:
    raise RuntimeError("请通过 NBU_USERNAME 和 NBU_PASSWORD 环境变量提供登录凭据")

# 随机字符集（与 JS 中的 $aes_chars 相同）
AES_CHARS = "ABCDEFGHJKMNPQRSTWXYZabcdefhijkmnprstwxyz2345678"


def random_uagent() -> str:
    """生成随机 User-Agent"""
    webkit = f"{random.randint(500, 540)}.{random.randint(0, 80)}"
    chrome = f"{random.randint(90, 140)}.0.{random.randint(0, 8000)}.{random.randint(0, 200)}"
    return f"Mozilla/5.0 (Linux; Android 15; wv) AppleWebKit/{webkit} (KHTML, like Gecko) Chrome/{chrome} Mobile Safari/{webkit} cpdaily/9.7.6 wisedu/9.7.6"


def random_string(length: int) -> str:
    """生成指定长度的随机字符串"""
    return "".join(random.choice(AES_CHARS) for _ in range(length))


def encrypt_password(password: str, salt: str) -> str:
    """
    加密密码
    对应 JS 中的 encryptPassword -> encryptAES -> getAesString
    """
    if not salt:
        return password

    # 生成随机前缀（64位）和 IV（16位）
    random_prefix = random_string(64)
    random_iv = random_string(16)

    # 准备要加密的数据
    data = random_prefix + password

    # 准备密钥和 IV（UTF-8 编码）
    key = salt.strip().encode("utf-8")
    iv = random_iv.encode("utf-8")

    # AES-CBC 加密，PKCS7 填充
    cipher = AES.new(key, AES.MODE_CBC, iv)
    encrypted = cipher.encrypt(pad(data.encode("utf-8"), AES.block_size))

    # Base64 编码
    return base64.b64encode(encrypted).decode("utf-8")


def get_login_params(html: str) -> dict:
    """
    从登录页面 HTML 中提取表单参数
    """
    params = {}

    # 提取 pwdEncryptSalt
    salt_match = re.search(r'id="pwdEncryptSalt"\s+value="([^"]*)"', html)
    if salt_match:
        params["salt"] = salt_match.group(1)

    # 提取 execution
    execution_match = re.search(r'name="execution"\s+value="([^"]*)"', html)
    if execution_match:
        params["execution"] = execution_match.group(1)

    # 提取 _eventId
    event_match = re.search(r'id="_eventId"\s+name="_eventId"\s+value="([^"]*)"', html)
    if event_match:
        params["_eventId"] = event_match.group(1)
    else:
        params["_eventId"] = "submit"

    # 提取 cllt (用户名登录方式)
    # 从 pwdLoginDiv 中提取
    cllt_match = re.search(
        r'id="pwdLoginDiv"[\s\S]*?id="cllt"\s+name="cllt"\s+value="([^"]*)"', html
    )
    if cllt_match:
        params["cllt"] = cllt_match.group(1)
    else:
        params["cllt"] = "userNameLogin"

    # 提取 dllt
    dllt_match = re.search(r'id="dllt"\s+name="dllt"\s+value="([^"]*)"', html)
    if dllt_match:
        params["dllt"] = dllt_match.group(1)
    else:
        params["dllt"] = "generalLogin"

    # lt 通常为空
    params["lt"] = ""

    return params


def login(username: str, password: str) -> str | None:
    """
    执行登录流程，返回 JSESSIONID
    """
    session = requests.Session()

    # 设置随机 User-Agent
    session.headers.update({"User-Agent": random_uagent()})

    # 基础 URL
    base_url = "https://uis.nbu.edu.cn/authserver/login"
    service_url = "https://attendance.nbu.edu.cn/api/getIndex"

    # Step 1: GET 登录页面，获取表单参数
    print("[Step 1] 获取登录页面...")
    login_page_url = f"{base_url}?service={service_url}"
    response = session.get(login_page_url)

    if response.status_code != 200:
        print(f"获取登录页面失败: {response.status_code}")
        return None

    html = response.text
    params = get_login_params(html)

    print(f"  - pwdEncryptSalt: {params.get('salt', 'N/A')}")
    print(f"  - execution: {params.get('execution', 'N/A')[:50]}...")
    print(f"  - cllt: {params.get('cllt', 'N/A')}")
    print(f"  - dllt: {params.get('dllt', 'N/A')}")

    if not params.get("salt") or not params.get("execution"):
        print("无法从页面中提取必要参数")
        return None

    # Step 2: 加密密码
    print("\n[Step 2] 加密密码...")
    encrypted_password = encrypt_password(password, params["salt"])
    print(f"  - 加密后的密码: {encrypted_password[:50]}...")

    # Step 3: POST 登录请求
    print("\n[Step 3] 提交登录请求...")

    post_data = {
        "username": username,
        "password": encrypted_password,
        "captcha": "",
        "_eventId": params["_eventId"],
        "cllt": params["cllt"],
        "dllt": params["dllt"],
        "lt": params["lt"],
        "execution": params["execution"],
    }

    # 需要禁用自动重定向来捕获 302
    login_response = session.post(
        f"{base_url}?service={requests.utils.quote(service_url, safe='')}",
        data=post_data,
        allow_redirects=False,
    )

    print(f"  - 响应状态码: {login_response.status_code}")

    if login_response.status_code == 302:
        redirect_url = login_response.headers.get("Location")
        print(f"  - 重定向地址: {redirect_url}")

        if "ticket=" in redirect_url:
            # Step 4: 访问带 ticket 的地址获取 JSESSIONID（允许重定向）
            print("\n[Step 4] 获取 JSESSIONID...")

            ticket_response = session.get(redirect_url, allow_redirects=True)
            print(f"  - 最终响应状态码: {ticket_response.status_code}")
            print(f"  - 最终 URL: {ticket_response.url}")

            # 从 session cookies 中获取 JSESSIONID（指定域名）
            jsessionid = session.cookies.get(
                "JSESSIONID", domain="attendance.nbu.edu.cn"
            )

            if jsessionid:
                print(f"\n[成功] JSESSIONID: {jsessionid}")
                return jsessionid
            else:
                # 尝试不指定域名，遍历所有 cookies
                for cookie in session.cookies:
                    if cookie.name == "JSESSIONID" and "attendance" in cookie.domain:
                        print(
                            f"\n[成功] JSESSIONID: {cookie.value} (域名: {cookie.domain})"
                        )
                        return cookie.value

                # 打印所有 cookies 以便调试
                print(f"  - 所有 cookies:")
                for cookie in session.cookies:
                    print(f"    {cookie.domain}: {cookie.name}={cookie.value[:20]}...")
                print("\n[失败] 未能从 cookies 中提取 JSESSIONID")
                return None
        else:
            print("\n[失败] 重定向地址中未包含 ticket")
            return None
    elif login_response.status_code in (200, 401):
        # 登录失败，可能是用户名密码错误或需要验证码
        # 尝试多种错误提示模式匹配
        error_patterns = [
            r'id="showErrorTip"[^>]*>([^<]+)</span>',
            r'class="auth_error"[^>]*>([^<]+)',
            r'<div[^>]*class="[^"]*error[^"]*"[^>]*>([^<]+)',
        ]
        error_msg = None
        for pattern in error_patterns:
            error_match = re.search(pattern, login_response.text)
            if error_match and error_match.group(1).strip():
                error_msg = error_match.group(1).strip()
                break

        if error_msg:
            print(f"\n[失败] 登录错误: {error_msg}")
        else:
            print(
                f"\n[失败] 登录失败 (状态码: {login_response.status_code})，请检查用户名密码或可能需要验证码"
            )
        return None
    else:
        print(f"\n[失败] 未预期的响应状态码: {login_response.status_code}")
        return None


def main():
    print("=" * 50)
    print("NBU 统一身份认证登录测试")
    print("=" * 50)
    print(f"用户名: {USERNAME}")
    print(f"密码: {'*' * len(PASSWORD)}")
    print("=" * 50)
    print()

    jsessionid = login(USERNAME, PASSWORD)

    if jsessionid:
        print("\n" + "=" * 50)
        print(f"登录成功！JSESSIONID = {jsessionid}")
        print("=" * 50)
    else:
        print("\n" + "=" * 50)
        print("登录失败，请检查配置")
        print("=" * 50)


if __name__ == "__main__":
    main()
