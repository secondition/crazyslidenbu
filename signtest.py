import requests
import random
import threading
import time


green = "\033[92m"  # open for sign
red = "\033[91m"  # not even signable
blue = "\033[94m"  # not signed
yellow = "\033[93m"  # unknown
gray = "\033[90m"  # sign expired
reset = "\033[0m"
coursePlanId = None
attendanceId = None
counter = 0
stop_event = threading.Event()


def random_uagent():
    webkit = f"{random.randint(500, 540)}.{random.randint(0, 80)}"
    chrome = f"{random.randint(90, 140)}.0.{random.randint(0, 8000)}.{random.randint(0, 200)}"
    return f"Mozilla/5.0 (Linux; Android 15; wv) AppleWebKit/{webkit} (KHTML, like Gecko) Chrome/{chrome} Mobile Safari/{webkit} cpdaily/9.7.6 wisedu/9.7.6"


def get_sessionid():
    global JSESSIONID
    while True:
        print("https://attendance.nbu.edu.cn/h5/student/")
        id = input("请输入 JSESSIONID: ")
        if id:
            JSESSIONID = id
            return


def get_courses():
    global coursePlanId, attendanceId
    url = "https://attendance.nbu.edu.cn/api/curriculum/student/getCourse"
    headers = {
        "Accept": "application/json, text/plain, */*",
        "Accept-Encoding": "gzip, deflate, br, zstd",
        "Accept-Language": "zh-CN,zh;q=0.9,en;q=0.8,en-GB;q=0.7,en-US;q=0.6",
        "Cookie": f"JSESSIONID={JSESSIONID}",
        "Dnt": "1",
        "User-Agent": random_uagent(),
    }
    session = requests.Session()
    session.trust_env = False
    response = session.get(url, headers=headers)
    data = response.json()
    if data.get("code") == 20000:
        courses = []
        print("-------------------------------")
        for i, course in enumerate(data.get("data", [])):
            coursename = f"{course['courseNumber']}-{course['courseName']}"
            course_coursePlanId = course.get("coursePlanId", None)  # 0 = not started
            course_attendanceId = course.get("attendanceId", None)
            course_attandanceState = course.get("attendanceState", None)
            match course_attandanceState:
                case 0:  # 未签
                    course_attandanceStateStr = f"{blue}{course_attandanceState} ×"
                case 1:  # 成功
                    course_attandanceStateStr = f"{green}{course_attandanceState} ✓"
                case 2:  # 迟到
                    course_attandanceStateStr = f"{yellow}{course_attandanceState} ✓*"
                case 3:  # 请假
                    course_attandanceStateStr = f"{green}{course_attandanceState} ✓$"
                case default:
                    course_attandanceStateStr = f"{yellow}{course_attandanceState} ?"
            if course_coursePlanId:
                print(green, end="")
            else:
                print(red, end="")
            print(f"[{i}]")
            print(f"课程: {coursename}")
            print(f"时间: {course['startTime']}~{course['endTime']}")
            if course_coursePlanId:
                print("已开启签到")
                print(f"  课程ID: {course_coursePlanId}")
                print(f"  签到ID: {course_attendanceId}")
                print(f"  签到状态: {course_attandanceStateStr}")
            else:
                print("未开启签到")
            print(f"{reset}-------------------------------")
            courses.append([course_coursePlanId, course_attendanceId])
        while True:
            try:
                userinput = input("请输入要签到的课程序号: ")
                if not userinput:
                    return ...
                courseindex = int(userinput)
                coursePlanId, attendanceId = courses[courseindex]
                assert coursePlanId is not None and attendanceId is not None
                break
            except KeyboardInterrupt:
                exit()
            except:
                print("Invalid.")
    else:
        print(f"{red}Failed to get courses:\n{data.get('msg')}{reset}")
        input()
        exit()


def get_code():
    while True:
        try:
            code = input("请输入四位数签到码(不填自动): ")
        except KeyboardInterrupt:
            exit()
        if len(code) == 4 and code.isdigit():
            return int(code)
        elif code.lower() == "q":
            exit()
        elif code == "":
            return None
        else:
            print("Invalid.")


def try_sign(code, lock=None, runnerid=0, session=None):
    if coursePlanId is None or attendanceId is None:
        raise ValueError("coursePlanId and attendanceId must be provided.")
    global counter
    url = "https://attendance.nbu.edu.cn/api/coursePlan/signByCourseCode"
    timenow = str(time.time_ns())[:13]
    headers = {
        "Accept": "application/json, text/plain, */*",
        "Accept-Encoding": "gzip, deflate, br, zstd",
        "Accept-Language": "zh-CN,zh;q=0.9,en;q=0.8,en-GB;q=0.7,en-US;q=0.6",
        "Connection": "keep-alive",
        "Content-Type": "application/json;charset=UTF-8",
        # "Cookie": f"SECKEY_ABVK={SECKEY_ABVK}; JSESSIONID={JSESSIONID}; iPlanetDirectoryPro={iPlanetDirectoryPro}",
        "Cookie": f"JSESSIONID={JSESSIONID}",
        "Dnt": "1",
        "Host": "attendance.nbu.edu.cn",
        "Referer": "https://attendance.nbu.edu.cn/h5/student/sign/",
        "Sec-Fetch-Dest": "empty",
        "Sec-Fetch-Mode": "cors",
        "Sec-Fetch-Site": "same-origin",
        "User-Agent": random_uagent(),
        "X-Requested-With": "com.wisedu.cpdaily.nbu",
    }
    params = {
        "timeNow": timenow,
        "courseCode": f"{code:04d}",
        "coursePlanId": coursePlanId,
        "attendanceId": attendanceId,
        "lng": "0",
        "lat": "0",
    }
    # 使用传入的session对象或创建新请求
    if session:
        response = session.get(url, headers=headers, params=params)
    else:
        session = requests.Session()
        response = session.get(url, headers=headers, params=params)
    if stop_event.is_set():
        return
    if lock is None:
        counter += 1
        if "错误" in response.text:
            print(
                f"[{runnerid:02d}] Code={code:04d} ({counter:04d}) -> ",
                f"{red}{response.text}{reset}",
            )
        elif "未设置" in response.text:
            print(
                f"[{runnerid:02d}] Code={code:04d} ({counter:04d}) -> ",
                f"{red}{response.text}{reset}",
            )
            raise SystemError("Done.")
        else:
            print(
                f"[{runnerid:02d}] Code={green}{code:04d}{reset} ({counter:04d}) -> ",
                f"{green}{response.text}{reset}",
            )
            raise SystemError("Done.")
    else:
        with lock:
            counter += 1
            if "错误" in response.text:
                print(
                    f"[{runnerid:02d}] Code={code:04d} ({counter:04d}) -> ",
                    f"{red}{response.text}{reset}",
                )
            elif "未设置" in response.text:
                print(
                    f"[{runnerid:02d}] Code={code:04d} ({counter:04d}) -> ",
                    f"{red}{response.text}{reset}",
                )
                raise SystemError("Done.")
            else:
                print(
                    f"[{runnerid:02d}] Code={green}{code:04d}{reset} ({counter:04d}) -> ",
                    f"{green}{response.text}{reset}",
                )
                raise SystemError("Done.")


get_sessionid()

while get_courses():
    pass

truecode = get_code()

if coursePlanId is None or attendanceId is None:
    raise ValueError("coursePlanId and attendanceId must be provided.")


def worker(codes, lock, num):
    # 创建session对象以复用连接
    session = requests.Session()
    for code in codes:
        if stop_event.is_set():
            break
        try:
            try_sign(code, lock, num, session)
        except SystemError:
            stop_event.set()
            break


if truecode is not None:
    # 创建session对象
    try:
        try_sign(truecode, None, 0)
    except SystemError:
        pass
    print("Done.")
    input()
    exit()

numbers = [i for i in range(10000)]
random.shuffle(numbers)
lock = threading.Lock()

# 创建n个线程
threads = []
n = 3
for i in range(n):
    thread_codes = numbers[i * len(numbers) // n : (i + 1) * len(numbers) // n]
    t = threading.Thread(target=worker, args=(thread_codes, lock, i))
    threads.append(t)
    t.start()

# 等待所有线程完成
for t in threads:
    t.join()

time.sleep(0.5)
print("All requests are done.")
input()
