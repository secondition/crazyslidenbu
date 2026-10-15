package com.example.nbu.network

import android.util.Log
import com.example.nbu.model.BrowserProfile
import com.example.nbu.model.Course
import com.example.nbu.model.CourseResponse
import com.example.nbu.model.SignResult
import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.Base64
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.random.Random

/**
 * NBU 统一身份认证与考勤服务
 */
class NbuApiService(private val browser: BrowserProfile) {

    companion object {
        private const val TAG = "NbuApiService"
        private const val AES_CHARS = "ABCDEFGHJKMNPQRSTWXYZabcdefhijkmnprstwxyz2345678"
        private const val AUTH_URL = "https://uis.nbu.edu.cn/authserver/"
        private const val LOGIN_URL = "${AUTH_URL}login"
        private const val SERVICE_URL = "https://attendance.nbu.edu.cn/api/getIndex"
        private const val USER_INFO_URL = "https://attendance.nbu.edu.cn/api/user/get/user/info"
        private const val COURSE_URL = "https://attendance.nbu.edu.cn/api/curriculum/student/getCourse"
        private const val SIGN_URL = "https://attendance.nbu.edu.cn/api/coursePlan/signByCourseCode"
        private val client = OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .callTimeout(15, TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .build()

        suspend fun preloadLoginPage() {
            withContext(Dispatchers.IO) {
                try {
                    client.newCall(Request.Builder().url(LOGIN_URL).head().build()).execute().use { }
                } catch (e: IOException) {
                    Log.d(TAG, "预热连接失败", e)
                }
            }
        }
    }

    private val gson = Gson()

    /**
     * 每次认证使用独立 CookieJar，避免多个账号共享认证状态。
     */
    suspend fun login(
        username: String,
        password: String,
        requestCode: suspend (String) -> String?
    ): Result<String> = result {
        val cookies = SimpleCookieJar()
        val loginClient = client.newBuilder().cookieJar(cookies).build()
        val loginUrl = LOGIN_URL.toHttpUrl().newBuilder()
            .addQueryParameter("service", SERVICE_URL)
            .build()
        val page = execute(loginClient, request(loginUrl.toString()).build())
        page.requireSuccess("获取登录页面失败")
        val salt = capture(page.text, """id="pwdEncryptSalt"\s+value="([^"]*)"""")
            ?: error("登录页缺少 pwdEncryptSalt")
        val execution = capture(page.text, """name="execution"\s+value="([^"]*)"""")
            ?: error("登录页缺少 execution")
        val eventId = capture(page.text, """id="_eventId"\s+name="_eventId"\s+value="([^"]*)"""")
            ?: "submit"
        val cllt = capture(page.text, """id="pwdLoginDiv"[\s\S]*?id="cllt"\s+name="cllt"\s+value="([^"]*)"""")
            ?: "userNameLogin"
        val dllt = capture(page.text, """id="dllt"\s+name="dllt"\s+value="([^"]*)"""")
            ?: "generalLogin"
        val fingerprintUrl = "${AUTH_URL}bfp/info".toHttpUrl().newBuilder()
            .addQueryParameter("bfp", browser.fingerprint)
            .build()
        execute(
            loginClient,
            request(fingerprintUrl.toString()).header("X-Requested-With", "XMLHttpRequest").build()
        ).requireSuccess("设置设备指纹失败")

        val form = FormBody.Builder()
            .add("username", username)
            .add("password", encryptPassword(password, salt))
            .add("captcha", "")
            .add("_eventId", eventId)
            .add("cllt", cllt)
            .add("dllt", dllt)
            .add("lt", "")
            .add("execution", execution)
            .build()
        val response = execute(loginClient, request(loginUrl.toString()).post(form).build())
        if (response.code !in listOf(301, 302, 303, 307, 308)) {
            error(parseLoginError(response.text) ?: "登录失败，请检查账号密码或验证码要求（HTTP ${response.code}）")
        }
        var finalResponse = followRedirects(loginClient, response)
        if (finalResponse.url.encodedPath == "/authserver/reAuthCheck/reAuthLoginView.do") {
            finalResponse = authenticateDevice(loginClient, finalResponse, requestCode)
        }
        finalResponse.requireSuccess("登录跳转失败")
        val jsessionId = cookies.getJSessionId(SERVICE_URL.toHttpUrl())
            ?: error(parseLoginError(finalResponse.text) ?: "登录未完成，未获取到考勤会话")
        check(validateSession(jsessionId, username).getOrThrow()) {
            parseLoginError(finalResponse.text) ?: "登录未完成，账号校验失败"
        }
        jsessionId
    }

    private suspend fun authenticateDevice(
        loginClient: OkHttpClient,
        page: HttpResponse,
        requestCode: suspend (String) -> String?
    ): HttpResponse {
        page.requireSuccess("获取设备认证页面失败")
        val userId = capture(page.text, """"reAuthUserId"\s*:\s*"([^"]*)"""")
            ?: error("设备认证页面缺少用户信息")
        val multifactor = capture(page.text, """"isMultifactor"\s*:\s*"([^"]*)"""")
            ?: error("设备认证页面缺少认证参数")
        val authType = capture(page.text, """"reAuthType"\s*:\s*"([^"]*)"""")
            ?: error("设备认证页面缺少认证方式")
        if (authType != "5") {
            val response = authPost(loginClient, "reAuthCheck/changeReAuthType.do", mapOf(
                "isMultifactor" to multifactor,
                "reAuthType" to "5",
                "service" to SERVICE_URL
            ))
            val data = gson.fromJson(response.text, ReAuthTypeResponse::class.java)
            check(data?.code == 1) { data?.message ?: "选择风华宁大认证失败" }
        }
        val sent = authPost(loginClient, "dynamicCode/getDynamicCodeByReauth.do", mapOf(
            "userName" to userId,
            "authCodeTypeName" to "reAuthCpdailyDynamicCodeType"
        ))
        val codeResponse = gson.fromJson(sent.text, DynamicCodeResponse::class.java)
        check(codeResponse?.res in listOf("cpdaily_success", "other_success", "success", "code_time_fail")) {
            codeResponse?.returnMessage ?: "发送风华宁大验证码失败"
        }
        val code = requestCode(codeResponse?.returnMessage ?: "请查看风华宁大收到的验证码")
            ?: throw CancellationException("已取消登录")
        val submitted = authPost(loginClient, "reAuthCheck/reAuthSubmit.do", mapOf(
            "service" to SERVICE_URL,
            "reAuthType" to "5",
            "isMultifactor" to multifactor,
            "dynamicCode" to code,
            "skipTmpReAuth" to "true"
        ))
        val authResponse = gson.fromJson(submitted.text, ReAuthResponse::class.java)
        check(!authResponse?.code.isNullOrBlank()) { "设备认证响应缺少结果" }
        check(authResponse?.code !in listOf("reAuth_failed", "reAuth_unauthorized")) {
            authResponse?.msg ?: "设备认证失败"
        }
        val url = LOGIN_URL.toHttpUrl().newBuilder().addQueryParameter("service", SERVICE_URL).build()
        return followRedirects(loginClient, execute(loginClient, request(url.toString()).build()))
    }

    private suspend fun authPost(
        loginClient: OkHttpClient,
        path: String,
        params: Map<String, String>
    ): HttpResponse {
        val form = FormBody.Builder()
        params.forEach { (name, value) -> form.add(name, value) }
        val response = execute(loginClient, request(AUTH_URL + path)
            .header("X-Requested-With", "XMLHttpRequest")
            .post(form.build())
            .build())
        response.requireSuccess("设备认证请求失败")
        return response
    }

    private suspend fun followRedirects(loginClient: OkHttpClient, initial: HttpResponse): HttpResponse {
        var response = initial
        repeat(10) {
            if (response.code !in listOf(301, 302, 303, 307, 308)) return response
            val location = response.location ?: error("登录跳转缺少 Location")
            val url = response.url.resolve(location) ?: error("无法解析登录跳转地址")
            response = execute(loginClient, request(url.toString()).build())
        }
        check(response.code !in listOf(301, 302, 303, 307, 308)) { "登录跳转次数过多" }
        return response
    }

    suspend fun validateSession(jsessionId: String, username: String): Result<Boolean> = result {
        val response = execute(client, request(USER_INFO_URL, jsessionId).build())
        if (response.code !in 200..299) return@result false
        val info = try {
            gson.fromJson(response.text, UserInfoResponse::class.java)
        } catch (_: JsonSyntaxException) {
            return@result false
        }
        info?.code == 20000 && info.success == true && info.data?.student?.xh == username
    }

    suspend fun getCourses(jsessionId: String): Result<List<Course>> = result {
        val response = execute(client, request(COURSE_URL, jsessionId).header("Dnt", "1").build())
        response.requireSession()
        response.requireSuccess("获取课程失败")
        val data = gson.fromJson(response.text, CourseResponse::class.java) ?: error("课程响应为空")
        if (data.code == 401 || data.msg?.contains("登录") == true || data.msg?.contains("未授权") == true) {
            throw SessionExpiredException()
        }
        check(data.code == 20000) { data.msg ?: "获取课程失败" }
        data.data.orEmpty().map { it.toCourse() }
    }

    suspend fun sign(
        jsessionId: String,
        coursePlanId: String,
        attendanceId: String,
        courseCode: Int
    ): Result<SignResult> = result {
        require(courseCode in 0..9999) { "请输入4位数字签到码" }
        val url = SIGN_URL.toHttpUrl().newBuilder()
            .addQueryParameter("timeNow", System.currentTimeMillis().toString())
            .addQueryParameter("courseCode", courseCode.toString().padStart(4, '0'))
            .addQueryParameter("coursePlanId", coursePlanId)
            .addQueryParameter("attendanceId", attendanceId)
            .addQueryParameter("lng", "0")
            .addQueryParameter("lat", "0")
            .build()
        val response = execute(client, request(url.toString(), jsessionId)
            .header("Content-Type", "application/json;charset=UTF-8")
            .header("Referer", "https://attendance.nbu.edu.cn/h5/student/sign/")
            .header("Dnt", "1")
            .header("Sec-Fetch-Dest", "empty")
            .header("Sec-Fetch-Mode", "cors")
            .header("Sec-Fetch-Site", "same-origin")
            .header("X-Requested-With", "com.wisedu.cpdaily.nbu")
            .build())
        response.requireSession()
        response.requireSuccess("签到请求失败")
        val data = gson.fromJson(response.text, SignResponse::class.java) ?: error("签到响应为空")
        if (data.code == 401 || data.msg?.contains("登录") == true) throw SessionExpiredException()
        val success = (data.code == 20000 || data.success == true) &&
            !data.msg.orEmpty().contains("错误") && !data.msg.orEmpty().contains("未设置")
        SignResult(success, courseCode, data.msg ?: response.text)
    }

    private fun request(url: String, jsessionId: String? = null): Request.Builder {
        val builder = Request.Builder().url(url)
            .header("User-Agent", browser.userAgent)
            .header("Accept", "application/json, text/plain, */*")
            .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8,en-GB;q=0.7,en-US;q=0.6")
        if (jsessionId != null) builder.header("Cookie", "JSESSIONID=$jsessionId")
        return builder
    }

    /**
     * 取消协程时同时取消网络请求，响应体在回调中读取并关闭。
     */
    private suspend fun execute(httpClient: OkHttpClient, request: Request): HttpResponse =
        suspendCancellableCoroutine { continuation ->
            val call = httpClient.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    continuation.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    val data = try {
                        response.use {
                            HttpResponse(it.code, it.request.url, it.header("Location"), it.body?.string().orEmpty())
                        }
                    } catch (e: Exception) {
                        continuation.resumeWithException(e)
                        return
                    }
                    continuation.resume(data)
                }
            })
        }

    private suspend fun <T> result(block: suspend () -> T): Result<T> = withContext(Dispatchers.IO) {
        try {
            Result.success(block())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun capture(html: String, pattern: String): String? =
        Regex(pattern).find(html)?.groupValues?.get(1)

    private fun parseLoginError(html: String): String? = listOf(
        """id="showErrorTip"[^>]*>([^<]+)</span>""",
        """class="auth_error"[^>]*>([^<]+)""",
        """<div[^>]*class="[^"]*error[^"]*"[^>]*>([^<]+)"""
    ).firstNotNullOfOrNull { capture(html, it)?.trim()?.takeIf(String::isNotEmpty) }

    private fun encryptPassword(password: String, salt: String): String {
        if (salt.isEmpty()) return password
        val prefix = randomString(64)
        val iv = IvParameterSpec(randomString(16).toByteArray(Charsets.UTF_8))
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(salt.trim().toByteArray(Charsets.UTF_8), "AES"), iv)
        return Base64.getEncoder().encodeToString(cipher.doFinal((prefix + password).toByteArray(Charsets.UTF_8)))
    }

    private fun randomString(length: Int): String =
        (1..length).map { AES_CHARS[Random.nextInt(AES_CHARS.length)] }.joinToString("")

    private data class HttpResponse(val code: Int, val url: HttpUrl, val location: String?, val text: String) {
        fun requireSuccess(message: String) {
            check(code in 200..299) { "$message（HTTP $code）" }
        }

        fun requireSession() {
            if (code == 401 || code in listOf(301, 302, 303, 307, 308) ||
                (code in 200..299 && text.trimStart().startsWith("<"))) {
                throw SessionExpiredException()
            }
        }
    }

    private data class ReAuthTypeResponse(val code: Int?, val message: String?)
    private data class DynamicCodeResponse(val res: String?, val returnMessage: String?)
    private data class ReAuthResponse(val code: String?, val msg: String?)
    private data class UserInfoResponse(val code: Int?, val success: Boolean?, val data: UserInfoData?)
    private data class UserInfoData(val student: Student?)
    private data class Student(val xh: String?)
    private data class SignResponse(val code: Int?, val msg: String?, val success: Boolean?)
}

class SessionExpiredException : IOException("登录状态已失效，请重新登录")
