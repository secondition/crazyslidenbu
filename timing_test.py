"""
测试登录各步骤的耗时
"""

import os
import time
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

AES_CHARS = "ABCDEFGHJKMNPQRSTWXYZabcdefhijkmnprstwxyz2345678"

def random_uagent():
    webkit = f"{random.randint(500, 540)}.{random.randint(0, 80)}"
    chrome = f"{random.randint(90, 140)}.0.{random.randint(0, 8000)}.{random.randint(0, 200)}"
    return f"Mozilla/5.0 (Linux; Android 15; wv) AppleWebKit/{webkit} (KHTML, like Gecko) Chrome/{chrome} Mobile Safari/{webkit} cpdaily/9.7.6 wisedu/9.7.6"

def random_string(length):
    return "".join(random.choice(AES_CHARS) for _ in range(length))

def encrypt_password(password, salt):
    if not salt:
        return password
    random_prefix = random_string(64)
    random_iv = random_string(16)
    data = random_prefix + password
    key = salt.strip().encode("utf-8")
    iv = random_iv.encode("utf-8")
    cipher = AES.new(key, AES.MODE_CBC, iv)
    encrypted = cipher.encrypt(pad(data.encode("utf-8"), AES.block_size))
    return base64.b64encode(encrypted).decode("utf-8")

def test_timing():
    import re
    
    session = requests.Session()
    session.headers.update({"User-Agent": random_uagent()})
    
    base_url = "https://uis.nbu.edu.cn/authserver/login"
    service_url = "https://attendance.nbu.edu.cn/api/getIndex"
    
    print("=" * 50)
    print("登录耗时测试")
    print("=" * 50)
    
    # Step 1: GET 登录页面
    print("\n[Step 1] GET 登录页面...")
    start = time.time()
    login_page_url = f"{base_url}?service={service_url}"
    response = session.get(login_page_url)
    step1_time = time.time() - start
    print(f"  耗时: {step1_time:.3f} 秒")
    
    # 提取参数
    html = response.text
    salt_match = re.search(r'id="pwdEncryptSalt"\s+value="([^"]*)"', html)
    execution_match = re.search(r'name="execution"\s+value="([^"]*)"', html)
    salt = salt_match.group(1) if salt_match else ""
    execution = execution_match.group(1) if execution_match else ""
    
    # Step 2: 加密密码
    print("\n[Step 2] 加密密码...")
    start = time.time()
    encrypted_password = encrypt_password(PASSWORD, salt)
    step2_time = time.time() - start
    print(f"  耗时: {step2_time:.6f} 秒")
    
    # Step 3: POST 登录
    print("\n[Step 3] POST 登录...")
    post_data = {
        "username": USERNAME,
        "password": encrypted_password,
        "captcha": "",
        "_eventId": "submit",
        "cllt": "userNameLogin",
        "dllt": "generalLogin",
        "lt": "",
        "execution": execution,
    }
    
    start = time.time()
    login_response = session.post(
        f"{base_url}?service={requests.utils.quote(service_url, safe='')}",
        data=post_data,
        allow_redirects=False,
    )
    step3_time = time.time() - start
    print(f"  耗时: {step3_time:.3f} 秒")
    print(f"  状态码: {login_response.status_code}")
    
    # Step 4: 跟踪重定向
    if login_response.status_code == 302:
        redirect_url = login_response.headers.get("Location")
        print(f"\n[Step 4] 跟踪重定向...")
        print(f"  重定向地址: {redirect_url[:80]}...")
        
        start = time.time()
        ticket_response = session.get(redirect_url, allow_redirects=True)
        step4_time = time.time() - start
        print(f"  耗时: {step4_time:.3f} 秒")
        print(f"  重定向次数: {len(ticket_response.history)}")
        
        jsessionid = session.cookies.get("JSESSIONID", domain="attendance.nbu.edu.cn")
        if jsessionid:
            print(f"  JSESSIONID: {jsessionid}")
    else:
        step4_time = 0
        print(f"\n[Step 4] 登录失败，无重定向")
    
    # 总结
    print("\n" + "=" * 50)
    print("耗时总结")
    print("=" * 50)
    print(f"Step 1 (GET 登录页面):  {step1_time:.3f} 秒")
    print(f"Step 2 (加密密码):      {step2_time:.6f} 秒")
    print(f"Step 3 (POST 登录):     {step3_time:.3f} 秒")
    print(f"Step 4 (跟踪重定向):    {step4_time:.3f} 秒")
    print("-" * 50)
    total = step1_time + step2_time + step3_time + step4_time
    print(f"总计:                   {total:.3f} 秒")
    print()
    print(f"Step 1 占比: {step1_time/total*100:.1f}%")
    print(f"Step 3 占比: {step3_time/total*100:.1f}%")
    print(f"Step 4 占比: {step4_time/total*100:.1f}%")

if __name__ == "__main__":
    test_timing()
