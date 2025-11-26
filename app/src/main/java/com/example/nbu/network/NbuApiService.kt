package com.example.nbu.network

import android.util.Base64
import android.util.Log
import com.example.nbu.model.Course
import com.example.nbu.model.CourseResponse
import com.example.nbu.model.SignResult
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.random.Random

/**
 * NBU API 服务
 * 参考 logintest.py 和 signtest.py 实现
 */
class NbuApiService {

    companion object {
        private const val TAG = "NbuApiService"
        private const val AES_CHARS = "ABCDEFGHJKMNPQRSTWXYZabcdefhijkmnprstwxyz2345678"
        private const val LOGIN_BASE_URL = "https://uis.nbu.edu.cn/authserver/login"
        private const val SERVICE_URL = "https://attendance.nbu.edu.cn/api/getIndex"
        private const val COURSE_URL = "https://attendance.nbu.edu.cn/api/curriculum/student/getCourse"
        private const val SIGN_URL = "https://attendance.nbu.edu.cn/api/coursePlan/signByCourseCode"
    }

    private val gson = Gson()
    
    // 使用共享的 CookieJar
    private val sharedCookieJar = SimpleCookieJar()
    
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .followRedirects(false) // 禁用自动重定向，手动处理
        .followSslRedirects(false)
        .cookieJar(sharedCookieJar)
        .build()

    /**
     * 验证 JSESSIONID 是否有效
     * 通过访问获取课程接口来验证
     */
    suspend fun validateSession(jsessionId: String): Boolean = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "验证 Session 有效性...")
            val request = Request.Builder()
                .url(COURSE_URL)
                .header("Accept", "application/json, text/plain, */*")
                .header("Cookie", "JSESSIONID=$jsessionId")
                .header("User-Agent", randomUserAgent())
                .get()
                .build()

            val response = client.newCall(request).execute()
            val responseBody = response.body?.string() ?: ""
            
            // 检查响应是否有效
            val isValid = response.isSuccessful && 
                          responseBody.contains("20000") && 
                          !responseBody.contains("未登录") &&
                          !responseBody.contains("登录失效")
            
            Log.d(TAG, "Session 验证结果: $isValid")
            return@withContext isValid
        } catch (e: Exception) {
            Log.e(TAG, "验证 Session 异常: ${e.message}")
            return@withContext false
        }
    }

    /**
     * 生成随机 User-Agent
     */
    private fun randomUserAgent(): String {
        val webkit = "${Random.nextInt(500, 541)}.${Random.nextInt(0, 81)}"
        val chrome = "${Random.nextInt(90, 141)}.0.${Random.nextInt(0, 8001)}.${Random.nextInt(0, 201)}"
        return "Mozilla/5.0 (Linux; Android 15; wv) AppleWebKit/$webkit (KHTML, like Gecko) Chrome/$chrome Mobile Safari/$webkit cpdaily/9.7.6 wisedu/9.7.6"
    }

    /**
     * 生成指定长度的随机字符串
     */
    private fun randomString(length: Int): String {
        return (1..length)
            .map { AES_CHARS[Random.nextInt(AES_CHARS.length)] }
            .joinToString("")
    }

    /**
     * AES 加密密码
     */
    private fun encryptPassword(password: String, salt: String): String {
        if (salt.isBlank()) return password

        val randomPrefix = randomString(64)
        val randomIv = randomString(16)
        val data = randomPrefix + password

        val key = salt.trim().toByteArray(Charsets.UTF_8)
        val iv = randomIv.toByteArray(Charsets.UTF_8)

        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        val secretKey = SecretKeySpec(key, "AES")
        val ivSpec = IvParameterSpec(iv)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey, ivSpec)

        val encrypted = cipher.doFinal(data.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(encrypted, Base64.NO_WRAP)
    }

    /**
     * 从登录页面 HTML 中提取表单参数
     */
    private fun getLoginParams(html: String): Map<String, String> {
        val params = mutableMapOf<String, String>()

        // 提取 pwdEncryptSalt
        val saltPattern = Pattern.compile("""id="pwdEncryptSalt"\s+value="([^"]*)"""")
        val saltMatcher = saltPattern.matcher(html)
        if (saltMatcher.find()) {
            params["salt"] = saltMatcher.group(1) ?: ""
        }

        // 提取 execution
        val executionPattern = Pattern.compile("""name="execution"\s+value="([^"]*)"""")
        val executionMatcher = executionPattern.matcher(html)
        if (executionMatcher.find()) {
            params["execution"] = executionMatcher.group(1) ?: ""
        }

        // 提取 _eventId - 默认 submit
        params["_eventId"] = "submit"

        // 从 pwdLoginDiv 中提取 cllt (用户名密码登录方式)
        // 参考 Python: id="pwdLoginDiv"[\s\S]*?id="cllt"\s+name="cllt"\s+value="([^"]*)"
        val clltPattern = Pattern.compile("""id="pwdLoginDiv"[\s\S]*?id="cllt"\s+name="cllt"\s+value="([^"]*)"""")
        val clltMatcher = clltPattern.matcher(html)
        if (clltMatcher.find()) {
            params["cllt"] = clltMatcher.group(1) ?: "userNameLogin"
            Log.d(TAG, "  - 从 pwdLoginDiv 提取 cllt: ${params["cllt"]}")
        } else {
            // 强制使用用户名密码登录
            params["cllt"] = "userNameLogin"
            Log.d(TAG, "  - 使用默认 cllt: userNameLogin")
        }

        // dllt - 通用登录
        params["dllt"] = "generalLogin"

        params["lt"] = ""

        return params
    }

    /**
     * 登录并返回 JSESSIONID
     */
    suspend fun login(username: String, password: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            // 清除旧的 Cookie
            sharedCookieJar.clear()
            
            // Step 1: 获取登录页面
            Log.d(TAG, "[Step 1] 获取登录页面...")
            val loginPageUrl = "$LOGIN_BASE_URL?service=$SERVICE_URL"
            val loginPageRequest = Request.Builder()
                .url(loginPageUrl)
                .header("User-Agent", randomUserAgent())
                .get()
                .build()

            val loginPageResponse = client.newCall(loginPageRequest).execute()
            if (!loginPageResponse.isSuccessful) {
                return@withContext Result.failure(Exception("获取登录页面失败: ${loginPageResponse.code}"))
            }

            val html = loginPageResponse.body?.string() ?: ""
            val params = getLoginParams(html)

            val salt = params["salt"]
            val execution = params["execution"]

            Log.d(TAG, "  - pwdEncryptSalt: $salt")
            Log.d(TAG, "  - execution: ${execution?.take(50)}...")
            Log.d(TAG, "  - cllt: ${params["cllt"]}")
            Log.d(TAG, "  - dllt: ${params["dllt"]}")

            if (salt.isNullOrBlank() || execution.isNullOrBlank()) {
                return@withContext Result.failure(Exception("无法从页面中提取必要参数"))
            }

            // Step 2: 加密密码
            Log.d(TAG, "[Step 2] 加密密码...")
            val encryptedPassword = encryptPassword(password, salt)
            Log.d(TAG, "  - 加密后的密码: ${encryptedPassword.take(50)}...")

            // Step 3: 提交登录请求
            Log.d(TAG, "[Step 3] 提交登录请求...")
            val encodedServiceUrl = URLEncoder.encode(SERVICE_URL, "UTF-8")
            val loginUrl = "$LOGIN_BASE_URL?service=$encodedServiceUrl"
            
            val cllt = params["cllt"] ?: "userNameLogin"
            val dllt = params["dllt"] ?: "generalLogin"
            Log.d(TAG, "  - 使用登录参数: cllt=$cllt, dllt=$dllt")

            val formBody = FormBody.Builder()
                .add("username", username)
                .add("password", encryptedPassword)
                .add("captcha", "")
                .add("_eventId", params["_eventId"] ?: "submit")
                .add("cllt", cllt)
                .add("dllt", dllt)
                .add("lt", params["lt"] ?: "")
                .add("execution", execution)
                .build()

            val loginRequest = Request.Builder()
                .url(loginUrl)
                .header("User-Agent", randomUserAgent())
                .header("Content-Type", "application/x-www-form-urlencoded")
                .post(formBody)
                .build()

            val loginResponse = client.newCall(loginRequest).execute()
            Log.d(TAG, "  - 响应状态码: ${loginResponse.code}")

            if (loginResponse.code == 302) {
                val redirectUrl = loginResponse.header("Location")
                Log.d(TAG, "  - 重定向地址: $redirectUrl")
                
                if (redirectUrl != null && redirectUrl.contains("ticket=")) {
                    // Step 4: 手动跟踪重定向获取 JSESSIONID
                    Log.d(TAG, "[Step 4] 获取 JSESSIONID...")
                    
                    var currentUrl: String = redirectUrl
                    var jsessionId: String? = null
                    var maxRedirects = 10
                    
                    while (maxRedirects > 0) {
                        val request = Request.Builder()
                            .url(currentUrl)
                            .header("User-Agent", randomUserAgent())
                            .get()
                            .build()
                        
                        val response = client.newCall(request).execute()
                        Log.d(TAG, "  - 访问: $currentUrl -> ${response.code}")
                        
                        // 检查 Set-Cookie 头
                        val cookies = response.headers("Set-Cookie")
                        for (cookie in cookies) {
                            Log.d(TAG, "  - Cookie: $cookie")
                            if (cookie.startsWith("JSESSIONID=") && currentUrl.contains("attendance.nbu.edu.cn")) {
                                jsessionId = cookie.substringAfter("JSESSIONID=").substringBefore(";")
                                Log.d(TAG, "  - 找到 JSESSIONID: $jsessionId")
                            }
                        }
                        
                        if (response.code in 301..303 || response.code == 307 || response.code == 308) {
                            val nextUrl = response.header("Location")
                            if (nextUrl != null) {
                                currentUrl = if (nextUrl.startsWith("http")) {
                                    nextUrl
                                } else {
                                    // 相对路径转绝对路径
                                    val baseUrl = currentUrl.substringBefore("?").substringBeforeLast("/")
                                    "$baseUrl/$nextUrl"
                                }
                                maxRedirects--
                                continue
                            }
                        }
                        
                        // 不是重定向，结束
                        break
                    }
                    
                    // 如果还没找到，尝试从 CookieJar 获取
                    if (jsessionId == null) {
                        jsessionId = sharedCookieJar.getJSessionId("attendance.nbu.edu.cn")
                        Log.d(TAG, "  - 从 CookieJar 获取 JSESSIONID: $jsessionId")
                    }
                    
                    if (jsessionId != null) {
                        Log.d(TAG, "[成功] JSESSIONID: $jsessionId")
                        return@withContext Result.success(jsessionId)
                    }
                    
                    // 打印所有 cookies 以便调试
                    Log.d(TAG, "  - 所有 cookies: ${sharedCookieJar.getAllCookies()}")
                    return@withContext Result.failure(Exception("未能从 cookies 中提取 JSESSIONID"))
                } else {
                    return@withContext Result.failure(Exception("重定向地址中未包含 ticket"))
                }
            } else if (loginResponse.code == 200 || loginResponse.code == 401) {
                // 登录失败
                val responseBody = loginResponse.body?.string() ?: ""
                Log.d(TAG, "  - 响应内容: ${responseBody.take(500)}")
                
                val errorPatterns = listOf(
                    """id="showErrorTip"[^>]*>([^<]+)</span>""",
                    """class="auth_error"[^>]*>([^<]+)""",
                    """<div[^>]*class="[^"]*error[^"]*"[^>]*>([^<]+)""",
                    """<span[^>]*id="msg"[^>]*class="[^"]*"[^>]*>([^<]+)</span>"""
                )
                
                for (pattern in errorPatterns) {
                    val matcher = Pattern.compile(pattern).matcher(responseBody)
                    if (matcher.find()) {
                        val errorMsg = matcher.group(1)?.trim()
                        if (!errorMsg.isNullOrBlank()) {
                            return@withContext Result.failure(Exception("登录错误: $errorMsg"))
                        }
                    }
                }
                
                return@withContext Result.failure(Exception("登录失败，请检查用户名密码或可能需要验证码"))
            } else {
                return@withContext Result.failure(Exception("未预期的响应状态码: ${loginResponse.code}"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "登录异常: ${e.message}")
            Log.e(TAG, "异常堆栈: ${e.stackTraceToString()}")
            return@withContext Result.failure(Exception("登录异常: ${e.message}", e))
        }
    }

    /**
     * 获取课程列表
     */
    suspend fun getCourses(jsessionId: String): Result<List<Course>> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(COURSE_URL)
                .header("Accept", "application/json, text/plain, */*")
                .header("Accept-Encoding", "gzip, deflate, br, zstd")
                .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
                .header("Cookie", "JSESSIONID=$jsessionId")
                .header("User-Agent", randomUserAgent())
                .get()
                .build()

            val response = client.newCall(request).execute()
            val responseBody = response.body?.string() ?: ""

            val courseResponse = gson.fromJson(responseBody, CourseResponse::class.java)
            
            if (courseResponse.code == 20000) {
                val courses = courseResponse.data?.map { it.toCourse() } ?: emptyList()
                return@withContext Result.success(courses)
            } else {
                return@withContext Result.failure(Exception(courseResponse.msg ?: "获取课程失败"))
            }
        } catch (e: Exception) {
            return@withContext Result.failure(e)
        }
    }

    /**
     * 签到
     */
    suspend fun sign(
        jsessionId: String,
        coursePlanId: String,
        attendanceId: String,
        courseCode: Int
    ): Result<SignResult> = withContext(Dispatchers.IO) {
        try {
            val timeNow = System.currentTimeMillis().toString()
            val codeStr = String.format("%04d", courseCode)
            
            val url = "$SIGN_URL?timeNow=$timeNow&courseCode=$codeStr&coursePlanId=$coursePlanId&attendanceId=$attendanceId&lng=0&lat=0"

            val request = Request.Builder()
                .url(url)
                .header("Accept", "application/json, text/plain, */*")
                .header("Accept-Encoding", "gzip, deflate, br, zstd")
                .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
                .header("Connection", "keep-alive")
                .header("Cookie", "JSESSIONID=$jsessionId")
                .header("Host", "attendance.nbu.edu.cn")
                .header("Referer", "https://attendance.nbu.edu.cn/h5/student/sign/")
                .header("User-Agent", randomUserAgent())
                .header("X-Requested-With", "com.wisedu.cpdaily.nbu")
                .get()
                .build()

            val response = client.newCall(request).execute()
            val responseBody = response.body?.string() ?: ""

            val signResult = when {
                responseBody.contains("错误") -> SignResult(false, courseCode, "签到码错误")
                responseBody.contains("未设置") -> SignResult(false, courseCode, "未设置签到码")
                responseBody.contains("成功") || responseBody.contains("20000") -> SignResult(true, courseCode, "签到成功")
                else -> SignResult(false, courseCode, responseBody)
            }

            return@withContext Result.success(signResult)
        } catch (e: Exception) {
            return@withContext Result.failure(e)
        }
    }
}
