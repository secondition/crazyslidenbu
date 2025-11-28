"""
测试 JSESSIONID 实际过期时间
每隔 1 分钟验证一次，直到返回登录失效
"""

import os
import time
import os
import random
import os
import requests
import os
import re
from datetime import datetime, timedelta
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
    """生成随机 User-Agent"""
    webkit = f"{random.randint(500, 540)}.{random.randint(0, 80)}"
    chrome = f"{random.randint(90, 140)}.0.{random.randint(0, 8000)}.{random.randint(0, 200)}"
    return f"Mozilla/5.0 (Linux; Android 15; wv) AppleWebKit/{webkit} (KHTML, like Gecko) Chrome/{chrome} Mobile Safari/{webkit} cpdaily/9.7.6 wisedu/9.7.6"


def random_string(length: int) -> str:
    """生成指定长度的随机字符串"""
    return "".join(random.choice(AES_CHARS) for _ in range(length))


def encrypt_password(password: str, salt: str) -> str:
    """加密密码"""
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


def get_login_params(html: str) -> dict:
    """从登录页面 HTML 中提取表单参数"""
    params = {}
    salt_match = re.search(r'id="pwdEncryptSalt"\s+value="([^"]*)"', html)
    if salt_match:
        params["salt"] = salt_match.group(1)
    execution_match = re.search(r'name="execution"\s+value="([^"]*)"', html)
    if execution_match:
        params["execution"] = execution_match.group(1)
    params["_eventId"] = "submit"
    cllt_match = re.search(
        r'id="pwdLoginDiv"[\s\S]*?id="cllt"\s+name="cllt"\s+value="([^"]*)"', html
    )
    if cllt_match:
        params["cllt"] = cllt_match.group(1)
    else:
        params["cllt"] = "userNameLogin"
    params["dllt"] = "generalLogin"
    params["lt"] = ""
    return params


def login(username: str, password: str) -> str | None:
    """执行登录流程，返回 JSESSIONID"""
    session = requests.Session()
    session.headers.update({"User-Agent": random_uagent()})
    
    base_url = "https://uis.nbu.edu.cn/authserver/login"
    service_url = "https://attendance.nbu.edu.cn/api/getIndex"
    
    print("[登录] 获取登录页面...")
    login_page_url = f"{base_url}?service={service_url}"
    response = session.get(login_page_url)
    
    if response.status_code != 200:
        print(f"[登录] 获取登录页面失败: {response.status_code}")
        return None
    
    html = response.text
    params = get_login_params(html)
    
    if not params.get("salt") or not params.get("execution"):
        print("[登录] 无法从页面中提取必要参数")
        return None
    
    print("[登录] 加密密码...")
    encrypted_password = encrypt_password(password, params["salt"])
    
    print("[登录] 提交登录请求...")
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
    
    login_response = session.post(
        f"{base_url}?service={requests.utils.quote(service_url, safe='')}",
        data=post_data,
        allow_redirects=False,
    )
    
    if login_response.status_code == 302:
        redirect_url = login_response.headers.get("Location")
        if "ticket=" in redirect_url:
            print("[登录] 获取 JSESSIONID...")
            ticket_response = session.get(redirect_url, allow_redirects=True)
            jsessionid = session.cookies.get("JSESSIONID", domain="attendance.nbu.edu.cn")
            if jsessionid:
                print(f"[登录] 成功！JSESSIONID: {jsessionid}")
                return jsessionid
    
    print("[登录] 登录失败")
    return None


def validate_session(jsessionid: str) -> tuple[bool, str]:
    """
    验证 JSESSIONID 是否有效
    返回: (是否有效, 响应信息)
    """
    url = "https://attendance.nbu.edu.cn/api/curriculum/student/getCourse"
    headers = {
        "Accept": "application/json, text/plain, */*",
        "Accept-Language": "zh-CN,zh;q=0.9,en;q=0.8,en-GB;q=0.7,en-US;q=0.6",
        "Cookie": f"JSESSIONID={jsessionid}",
        "Dnt": "1",
        "User-Agent": random_uagent(),
    }
    
    try:
        session = requests.Session()
        session.trust_env = False
        response = session.get(url, headers=headers, timeout=30)
        data = response.json()
        
        code = data.get("code")
        msg = data.get("msg", "")
        
        # code 20000 表示成功
        if code == 20000:
            return True, f"有效 (code={code}, msg={msg})"
        else:
            return False, f"失效 (code={code}, msg={msg})"
            
    except Exception as e:
        return False, f"请求异常: {e}"


def main():
    print("=" * 60)
    print("JSESSIONID 过期时间测试")
    print("=" * 60)
    
    # 选择测试模式
    print("\n测试模式:")
    print("  1. 持续验证（每分钟检测，会刷新Session）")
    print("  2. 静默等待（等待指定时间后再检测，测试真实过期时间）")
    print()
    mode = input("请选择模式 (1/2): ").strip()
    
    # 自动登录获取 JSESSIONID
    print()
    print(f"用户名: {USERNAME}")
    print(f"密码: {'*' * len(PASSWORD)}")
    print()
    
    jsessionid = login(USERNAME, PASSWORD)
    
    if not jsessionid:
        print("获取 JSESSIONID 失败，退出测试")
        return
    
    print()
    print(f"JSESSIONID: {jsessionid}")
    
    if mode == "2":
        # 静默等待模式
        run_silent_mode(jsessionid)
    else:
        # 持续验证模式
        run_continuous_mode(jsessionid)


def run_continuous_mode(jsessionid: str):
    """持续验证模式（每分钟检测）"""
    interval = 60  # 1 分钟
    
    print()
    print(f"模式: 持续验证（每 {interval} 秒检测一次）")
    print("注意: 此模式会不断刷新 Session，无法测试真实过期时间")
    print()
    print("开始测试...")
    print("-" * 60)
    
    start_time = time.time()
    check_count = 0
    
    while True:
        check_count += 1
        elapsed = time.time() - start_time
        elapsed_str = str(timedelta(seconds=int(elapsed)))
        
        is_valid, message = validate_session(jsessionid)
        
        now = datetime.now().strftime("%H:%M:%S")
        
        if is_valid:
            print(f"[{now}] 第 {check_count:3d} 次检测 | 已过 {elapsed_str:>8} | ✓ {message}")
            time.sleep(interval)
        else:
            print(f"[{now}] 第 {check_count:3d} 次检测 | 已过 {elapsed_str:>8} | ✗ {message}")
            print_result(elapsed, check_count)
            break


def run_silent_mode(jsessionid: str):
    """静默等待模式（等待后再检测）"""
    print()
    print("模式: 静默等待（不访问 API，等待指定时间后检测）")
    print()
    
    # 输入等待时间
    wait_minutes = input("请输入等待时间（分钟，建议从 30 开始）: ").strip()
    try:
        wait_minutes = int(wait_minutes)
    except:
        wait_minutes = 30
    
    wait_seconds = wait_minutes * 60
    
    print()
    print(f"将等待 {wait_minutes} 分钟后检测 Session 是否有效")
    print(f"在此期间不会发送任何请求")
    print()
    print("-" * 60)
    
    start_time = time.time()
    
    # 等待期间显示倒计时
    for remaining in range(wait_seconds, 0, -60):
        elapsed = time.time() - start_time
        now = datetime.now().strftime("%H:%M:%S")
        print(f"[{now}] 已等待 {int(elapsed/60):3d} 分钟 | 剩余 {int(remaining/60):3d} 分钟...")
        time.sleep(min(60, remaining))
    
    # 等待结束，检测
    elapsed = time.time() - start_time
    elapsed_str = str(timedelta(seconds=int(elapsed)))
    now = datetime.now().strftime("%H:%M:%S")
    
    print()
    print(f"[{now}] 等待结束，开始检测...")
    
    is_valid, message = validate_session(jsessionid)
    
    print()
    print("=" * 60)
    if is_valid:
        print(f"结果: ✓ Session 仍然有效！")
        print(f"等待时间: {elapsed_str}")
        print(f"说明: 真实过期时间 > {wait_minutes} 分钟")
        print()
        print("建议: 增加等待时间重新测试")
    else:
        print(f"结果: ✗ Session 已失效！")
        print(f"等待时间: {elapsed_str}")
        print(f"说明: 真实过期时间 < {wait_minutes} 分钟")
        print()
        print("建议: 减少等待时间重新测试，二分查找精确值")
    print("=" * 60)


def print_result(elapsed: float, check_count: int):
    """打印测试结果"""
    elapsed_str = str(timedelta(seconds=int(elapsed)))
    print()
    print("=" * 60)
    print("测试结束！")
    print("=" * 60)
    print(f"JSESSIONID 实际有效时长: {elapsed_str}")
    print(f"总计检测次数: {check_count}")
    print(f"精确秒数: {int(elapsed)} 秒")
    print(f"精确分钟: {elapsed / 60:.2f} 分钟")
    print(f"精确小时: {elapsed / 3600:.2f} 小时")


if __name__ == "__main__":
    try:
        main()
    except KeyboardInterrupt:
        print("\n\n用户中断测试")
