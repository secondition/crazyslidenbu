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
    
    // 签到专用 client，不使用 CookieJar，确保手动设置的 Cookie header 不被覆盖
    private val signClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    /**
     * 预热连接（DNS + TLS）
     * 通过 HEAD 请求预热连接，不消耗 execution 参数
     */
    suspend fun preloadLoginPage() = withContext(Dispatchers.IO) {
        try {
            val startTime = System.currentTimeMillis()
            Log.d(TAG, "[预热] 开始预热连接...")
            
            // 使用 HEAD 请求预热 DNS + TLS 连接，不获取实际内容
            val warmupRequest = Request.Builder()
                .url(LOGIN_BASE_URL)
                .head()  // HEAD 请求，只建立连接，不获取响应体
                .header("Connection", "keep-alive")
                .header("User-Agent", randomUserAgent())
                .build()

            val response = client.newCall(warmupRequest).execute()
            response.close()  // 立即关闭，但连接会保持在连接池中
            
            Log.d(TAG, "[预热] 完成，耗时: ${System.currentTimeMillis() - startTime} ms，连接已缓存")
        } catch (e: Exception) {
            Log.e(TAG, "[预热] 异常: ${e.message}")
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
            val totalStart = System.currentTimeMillis()
            
            // 清除旧的 Cookie
            sharedCookieJar.clear()
            
            // Step 1: 获取登录页面（连接已通过预热缓存）
            var stepStart = System.currentTimeMillis()
            Log.d(TAG, "[Step 1] 获取登录页面...")
            
            val loginPageUrl = "$LOGIN_BASE_URL?service=$SERVICE_URL"
            val loginPageRequest = Request.Builder()
                .url(loginPageUrl)
                .header("Accept", "*/*")
                .header("Connection", "keep-alive")
                .header("User-Agent", randomUserAgent())
                .get()
                .build()

            val loginPageResponse = client.newCall(loginPageRequest).execute()
            Log.d(TAG, "  [耗时] Step 1: ${System.currentTimeMillis() - stepStart} ms")
            
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
            stepStart = System.currentTimeMillis()
            Log.d(TAG, "[Step 2] 加密密码...")
            val encryptedPassword = encryptPassword(password, salt)
            Log.d(TAG, "  [耗时] Step 2: ${System.currentTimeMillis() - stepStart} ms")
            Log.d(TAG, "  - 加密后的密码: ${encryptedPassword.take(50)}...")

            // Step 3: 提交登录请求
            stepStart = System.currentTimeMillis()
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

            // 模拟 Python requests.Session() 的默认 headers
            val loginRequest = Request.Builder()
                .url(loginUrl)
                .header("Accept", "*/*")
                .header("Connection", "keep-alive")
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("User-Agent", randomUserAgent())
                .post(formBody)
                .build()

            val loginResponse = client.newCall(loginRequest).execute()
            Log.d(TAG, "  [耗时] Step 3: ${System.currentTimeMillis() - stepStart} ms")
            Log.d(TAG, "  - 响应状态码: ${loginResponse.code}")

            if (loginResponse.code == 302) {
                val redirectUrl = loginResponse.header("Location")
                Log.d(TAG, "  - 重定向地址: $redirectUrl")
                
                if (redirectUrl != null && redirectUrl.contains("ticket=")) {
                    // Step 4: 手动跟踪重定向获取 JSESSIONID
                    stepStart = System.currentTimeMillis()
                    Log.d(TAG, "[Step 4] 获取 JSESSIONID...")
                    
                    var currentUrl: String = redirectUrl
                    var jsessionId: String? = null
                    var maxRedirects = 10
                    
                    while (maxRedirects > 0) {
                        // 模拟 Python requests.Session() 的默认 headers
                        val request = Request.Builder()
                            .url(currentUrl)
                            .header("Accept", "*/*")
                            .header("Connection", "keep-alive")
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
                    Log.d(TAG, "  [耗时] Step 4: ${System.currentTimeMillis() - stepStart} ms")
                    
                    // 如果还没找到，尝试从 CookieJar 获取
                    if (jsessionId == null) {
                        jsessionId = sharedCookieJar.getJSessionId("attendance.nbu.edu.cn")
                        Log.d(TAG, "  - 从 CookieJar 获取 JSESSIONID: $jsessionId")
                    }
                    
                    if (jsessionId != null) {
                        Log.d(TAG, "[成功] JSESSIONID: $jsessionId")
                        Log.d(TAG, "[总耗时] 登录完成: ${System.currentTimeMillis() - totalStart} ms")
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
            val startTime = System.currentTimeMillis()
            Log.d(TAG, "[getCourses] 开始获取课程列表...")
            
            // 模拟浏览器环境的 headers，与 signtest.py 的 get_courses 保持一致
            val request = Request.Builder()
                .url(COURSE_URL)
                .header("Accept", "application/json, text/plain, */*")
                .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8,en-GB;q=0.7,en-US;q=0.6")
                .header("Cookie", "JSESSIONID=$jsessionId")
                .header("Dnt", "1")
                .header("User-Agent", randomUserAgent())
                .get()
                .build()

            val response = client.newCall(request).execute()
            val responseBody = response.body?.string() ?: ""
            
            Log.d(TAG, "[getCourses] 耗时: ${System.currentTimeMillis() - startTime} ms")
            Log.d(TAG, "获取课程响应: ${responseBody.take(500)}")
            
            // 检查响应是否为空
            if (responseBody.isBlank()) {
                return@withContext Result.failure(Exception("服务器返回空响应"))
            }
            
            // 检查是否是 HTML 响应（可能是登录页面重定向）
            if (responseBody.trimStart().startsWith("<") || responseBody.contains("<!DOCTYPE")) {
                Log.w(TAG, "响应为 HTML，可能需要重新登录")
                return@withContext Result.failure(Exception("登录失效，请重新登录"))
            }
            
            // 尝试解析 JSON
            val courseResponse = try {
                gson.fromJson(responseBody, CourseResponse::class.java)
            } catch (jsonException: Exception) {
                Log.e(TAG, "JSON 解析失败: ${jsonException.message}")
                Log.e(TAG, "原始响应: $responseBody")
                return@withContext Result.failure(Exception("解析课程数据失败，请稍后重试"))
            }
            
            // 检查解析结果是否为 null
            if (courseResponse == null) {
                return@withContext Result.failure(Exception("解析课程数据失败"))
            }
            
            if (courseResponse.code == 20000) {
                val courses = courseResponse.data?.mapNotNull { 
                    try {
                        it.toCourse()
                    } catch (e: Exception) {
                        Log.e(TAG, "转换课程数据失败: ${e.message}")
                        null
                    }
                } ?: emptyList()
                return@withContext Result.success(courses)
            } else {
                val errorMsg = courseResponse.msg ?: "获取课程失败"
                // 检查是否需要重新登录
                if (errorMsg.contains("登录") || errorMsg.contains("未授权") || courseResponse.code == 401) {
                    return@withContext Result.failure(Exception("登录失效，请重新登录"))
                }
                return@withContext Result.failure(Exception(errorMsg))
            }
        } catch (e: Exception) {
            Log.e(TAG, "获取课程异常: ${e.message}")
            Log.e(TAG, "异常堆栈: ${e.stackTraceToString()}")
            // 显示原始错误信息，便于调试
            val errorMessage = e.message ?: "未知错误"
            return@withContext Result.failure(Exception("获取课程失败: $errorMessage"))
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
            
            Log.d(TAG, "签到参数: coursePlanId=$coursePlanId, attendanceId=$attendanceId, courseCode=$codeStr, timeNow=$timeNow")
            
            // 使用 HttpUrl.Builder 正确构建 URL，自动处理参数编码
            val url = okhttp3.HttpUrl.Builder()
                .scheme("https")
                .host("attendance.nbu.edu.cn")
                .addPathSegments("api/coursePlan/signByCourseCode")
                .addQueryParameter("timeNow", timeNow)
                .addQueryParameter("courseCode", codeStr)
                .addQueryParameter("coursePlanId", coursePlanId)
                .addQueryParameter("attendanceId", attendanceId)
                .addQueryParameter("lng", "0")
                .addQueryParameter("lat", "0")
                .build()
            
            Log.d(TAG, "签到请求URL: $url")

            // 模拟浏览器环境的完整 headers，与 signtest.py 保持一致
            // 注意：不设置 Accept-Encoding，OkHttp 会自动处理压缩
            val request = Request.Builder()
                .url(url)
                .header("Accept", "application/json, text/plain, */*")
                .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8,en-GB;q=0.7,en-US;q=0.6")
                .header("Connection", "keep-alive")
                .header("Content-Type", "application/json;charset=UTF-8")
                .header("Cookie", "JSESSIONID=$jsessionId")
                .header("Dnt", "1")
                .header("Host", "attendance.nbu.edu.cn")
                .header("Referer", "https://attendance.nbu.edu.cn/h5/student/sign/")
                .header("Sec-Fetch-Dest", "empty")
                .header("Sec-Fetch-Mode", "cors")
                .header("Sec-Fetch-Site", "same-origin")
                .header("User-Agent", randomUserAgent())
                .header("X-Requested-With", "com.wisedu.cpdaily.nbu")
                .get()
                .build()

            // 使用 signClient 而不是 client，避免 CookieJar 覆盖手动设置的 Cookie
            val response = signClient.newCall(request).execute()
            val responseBody = response.body?.string() ?: ""
            
            Log.d(TAG, "签到响应: $responseBody, HTTP状态: ${response.code}, 使用JSESSIONID: ${jsessionId.take(10)}...")

            // 尝试解析 JSON 获取原始错误消息
            val jsonResponse = try {
                gson.fromJson(responseBody, SignResponse::class.java)
            } catch (e: Exception) {
                null
            }
            
            // 判断是否成功：code == 20000 或响应不包含错误关键字
            val isSuccess = jsonResponse?.code == 20000 || 
                (!responseBody.contains("错误") && !responseBody.contains("未设置") && jsonResponse?.success == true)
            
            // 使用服务器返回的原始消息
            val message = jsonResponse?.msg ?: responseBody
            
            val signResult = SignResult(isSuccess, courseCode, message)

            return@withContext Result.success(signResult)
        } catch (e: Exception) {
            return@withContext Result.failure(e)
        }
    }
}

/**
 * 签到响应数据结构
 */
data class SignResponse(
    val code: Int?,
    val msg: String?,
    val success: Boolean?
)
