package com.example.nbu.network

import android.net.Uri
import android.os.Build
import android.util.Log
import com.example.nbu.model.ActivityCode
import com.example.nbu.model.Course
import com.example.nbu.model.CourseResponse
import com.example.nbu.model.PlatformSession
import com.example.nbu.model.SignResult
import com.example.nbu.util.UserManager
import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonSyntaxException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Cookie
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
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
 * NBU 平台认证与考勤服务
 */
class NbuApiService(private val username: String, private val userManager: UserManager) {

    companion object {
        private const val TAG = "NbuApiService"
        private const val AES_CHARS = "ABCDEFGHJKMNPQRSTWXYZabcdefhijkmnprstwxyz2345678"
        private const val AUTH_URL = "https://uis.nbu.edu.cn/authserver/"
        private const val LOGIN_URL = "${AUTH_URL}login"
        private const val PLATFORM_URL = "https://mobile.campushoy.com/"
        private const val ACTIVITY_ENTRY = "${ActivityCode.BASE_URL}/mobile/entry-scan/index.html"
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
    private val browser = userManager.getBrowserProfile(username)
    private val cookies = SimpleCookieJar(userManager.getCookies(username))
    private val accountClient = client.newBuilder().cookieJar(cookies).build()
    private var platform = userManager.getPlatformSession(username)

    suspend fun login(
        password: String,
        requestCode: suspend (String) -> String?
    ): Result<String> = result {
        if (userManager.getUser(username)?.password != password) {
            userManager.clearSession(username)
            for (url in listOf(AUTH_URL, SERVICE_URL)) {
                cookies.loadForRequest(url.toHttpUrl()).filter {
                    it.name in listOf("CASTGC", "AUTHTGC", "JSESSIONID")
                }.forEach { cookies.remove(it.name, it.domain) }
            }
            userManager.saveCookies(username, cookies.snapshot())
        }
        val cached = userManager.getCachedSession(username)
        if (cached != null && validateSession(cached)) return@result cached
        userManager.clearSession(username)
        val loginUrl = LOGIN_URL.toHttpUrl().newBuilder()
            .addQueryParameter("service", SERVICE_URL)
            .build()
        val loginRequest = request(loginUrl.toString()).build()
        var page = followRedirects(execute(accountClient, loginRequest))
        if (page.url.host == loginUrl.host && page.url.encodedPath == loginUrl.encodedPath) {
            val response = submitPassword(page, password, request(page.url.toString()).build())
            check(response.isRedirect) {
                parseLoginError(response.text) ?: "登录失败，请检查账号密码或验证码要求（HTTP ${response.code}）"
            }
            page = followRedirects(response)
        }
        if (page.url.encodedPath == "/authserver/reAuthCheck/reAuthLoginView.do") {
            page = followRedirects(authenticateDevice(page, loginRequest, requestCode))
        }
        page.requireSuccess("登录跳转失败")
        val jsessionId = cookies.getJSessionId(SERVICE_URL.toHttpUrl())
            ?: error(parseLoginError(page.text) ?: "登录未完成，未获取到考勤会话")
        check(validateSession(jsessionId)) { parseLoginError(page.text) ?: "登录未完成，账号校验失败" }
        userManager.saveSession(username, jsessionId)
        jsessionId
    }

    private suspend fun ensurePlatformSession(password: String, requestCode: suspend (String) -> String?) {
        val cached = platform
        if (cached != null) {
            val data = try {
                val response = platformPost("v6/auth/authentication/new/validation", mapOf(
                    "tgc" to PlatformCipher.des(cached.tgc, "XCE927=="),
                    "idsToken" to PlatformCipher.des(cached.idsToken, "XCE927==")
                ))
                gson.fromJson(response, PlatformLoginResult::class.java)
            } catch (_: SessionExpiredException) {
                null
            }
            if (data != null && data.tgcStatus != "tgcInvalidate") {
                savePlatformSession(data, cached.idsToken)
                return
            }
        }
        platform = null
        userManager.clearPlatformSession(username)
        for (domain in listOf("campushoy.com", "cpdaily.com")) cookies.remove("sessionToken", domain)
        userManager.saveCookies(username, cookies.snapshot())

        val keys = PlatformCipher.secretResponse(platformPost(
            "app/auth/dynamic/secret/getSecretKey/v-920", PlatformCipher.secretRequest()
        ).asString)
        check(keys.size >= 3) { "平台密钥响应不完整" }
        val key = keys[1]
        val tenantUrl = "${PLATFORM_URL}v6/config/guest/tenant/info/v-8222".toHttpUrl().newBuilder()
            .addQueryParameter("a", PlatformCipher.encrypt("nbu", key))
            .addQueryParameter("b", "first_v4")
            .build()
        val tenantData = platformData(execute(accountClient, platformRequest(tenantUrl.toString()).build())).asString
        val tenant = gson.fromJson(PlatformCipher.decrypt(tenantData, key), Array<Tenant>::class.java)
            .firstOrNull() ?: error("平台未返回学校配置")
        check(tenant.id == "nbu" && tenant.joinType == "NOTCLOUD") { "无法识别学校登录方式" }
        val ticket = mobileLogin(tenant, password, requestCode)
        val payload = gson.toJson(mapOf("c" to tenant.id, "d" to ticket))
        val response = platformPost("app/auth/authentication/notcloud/login/v-8222", mapOf(
            "a" to PlatformCipher.encrypt(payload, key),
            "b" to "first_v4"
        )).asString
        savePlatformSession(gson.fromJson(PlatformCipher.decrypt(response, key), PlatformLoginResult::class.java), ticket)
    }

    private suspend fun mobileLogin(
        tenant: Tenant,
        password: String,
        requestCode: suspend (String) -> String?
    ): String {
        val entry = "${tenant.idsUrl.trimEnd('/')}/mobile/auth".toHttpUrl().newBuilder()
            .addQueryParameter("appId", tenant.appId)
            .build()
        var page = execute(accountClient, request(entry.toString()).header("CpdailyAuthType", "Login").build())
        var loginUrl: HttpUrl? = null
        var submitted = false
        repeat(10) {
            if (page.url.encodedPath == "/authserver/login") loginUrl = page.url
            when {
                page.isRedirect -> {
                    val target = page.redirectUrl()
                    if (target.host == entry.host && target.encodedPath == "${entry.encodedPath.substringBeforeLast('/')}/default.html") {
                        return Uri.decode(capture(target.toString(), """mobile_\w*=(.*)""")
                            ?: error("移动端回调缺少登录票据"))
                    }
                    page = execute(accountClient, request(target.toString()).header("CpdailyAuthType", "Login").build())
                }
                page.url.encodedPath == "/authserver/reAuthCheck/reAuthLoginView.do" -> {
                    val resumeRequest = request((loginUrl ?: error("认证页面缺少登录入口")).toString())
                        .header("CpdailyAuthType", "Login").build()
                    page = authenticateDevice(page, resumeRequest, requestCode)
                }
                page.url.encodedPath == "/authserver/login" && !submitted -> {
                    submitted = true
                    page = submitPassword(page, password, request(page.url.toString()).header("CpdailyAuthType", "Login").build())
                }
                else -> error(parseLoginError(page.text) ?: "移动端登录未完成（HTTP ${page.code}）")
            }
        }
        error("移动端登录跳转次数过多")
    }

    private fun savePlatformSession(data: PlatformLoginResult, idsToken: String) {
        check(data.deviceStatus != "exception") {
            data.deviceExceptionMsg ?: "平台未识别此账号的设备身份"
        }
        check(data.status == "cpdailyPage" && data.authStatus == "auth") {
            data.msgTip ?: "平台账号认证未完成"
        }
        val token = data.sessionToken?.takeIf { it.isNotBlank() } ?: error("平台响应缺少登录会话")
        val userId = data.userId?.takeIf { it.isNotBlank() } ?: error("平台响应缺少用户信息")
        val tgc = data.tgc?.takeIf { it.isNotBlank() } ?: error("平台响应缺少学校登录凭据")
        val session = PlatformSession(token, userId, tgc, idsToken)
        platform = session
        userManager.savePlatformSession(username, session)
        for (domain in listOf("campushoy.com", "cpdaily.com")) {
            val values = mapOf("sessionToken" to token, "tenantId" to "nbu", "clientType" to "cpdaily_student", "standAlone" to "1")
            cookies.saveFromResponse("https://$domain/".toHttpUrl(), values.map { (name, value) ->
                Cookie.Builder().name(name).value(value).domain(domain).path("/").build()
            })
        }
        restoreTgc(tgc)
    }

    private fun restoreTgc(tgc: String) {
        val url = AUTH_URL.toHttpUrl()
        val values = listOf("CASTGC", "AUTHTGC").map { name ->
            cookies.remove(name, url.host)
            Cookie.Builder().name(name).value(tgc).hostOnlyDomain(url.host).path(url.encodedPath.trimEnd('/')).build()
        }
        cookies.saveFromResponse(url, values)
        userManager.saveCookies(username, cookies.snapshot())
    }

    private fun platformRequest(url: String): Request.Builder {
        val token = PlatformCipher.des(platform?.sessionToken.orEmpty(), "XCE927==")
        val info = gson.toJson(linkedMapOf(
            "systemName" to "android", "systemVersion" to Build.VERSION.RELEASE, "model" to Build.MODEL,
            "deviceId" to requireNotNull(userManager.getDeviceId(username)), "appVersion" to "10.2.7", "cpdailyVersion" to "9.9.10",
            "lon" to 0.0, "lat" to 0.0, "userId" to platform?.userId.orEmpty()
        ))
        return request(url)
            .header("sessionTokenKey", token)
            .header("SessionToken", token)
            .header("clientType", "cpdaily_student")
            .header("tenantId", "nbu")
            .header("deviceType", "1")
            .header("CpdailyClientType", "CPDAILY")
            .header("CpdailyStandAlone", "1")
            .header("CpdailyInfo", PlatformCipher.des(info, "ST83=@XV"))
    }

    private suspend fun platformPost(path: String, data: Map<String, String>): JsonElement {
        val body = gson.toJson(data).toRequestBody("application/json; charset=utf-8".toMediaType())
        return platformData(execute(accountClient, platformRequest(PLATFORM_URL + path).post(body).build()))
    }

    private fun platformData(response: HttpResponse): JsonElement {
        if (response.code == 401) throw SessionExpiredException()
        response.requireSuccess("平台请求失败")
        val data = gson.fromJson(response.text, PlatformResponse::class.java) ?: error("平台响应为空")
        val code = data.errCode ?: data.code ?: data.status
        if (code in listOf("-206", "1202")) throw SessionExpiredException()
        check(code in listOf("0", "200")) { data.errMsg ?: data.message ?: "平台请求失败（$code）" }
        return data.data ?: error("平台响应缺少数据")
    }

    private suspend fun submitPassword(page: HttpResponse, password: String, loginRequest: Request): HttpResponse {
        page.requireSuccess("获取登录页面失败")
        val salt = capture(page.text, """id="pwdEncryptSalt"\s+value="([^"]*)"""")
            ?: error(parseLoginError(page.text) ?: "登录页缺少 pwdEncryptSalt")
        val execution = capture(page.text, """name="execution"\s+value="([^"]*)"""")
            ?: error("登录页缺少 execution")
        val eventId = capture(page.text, """id="_eventId"\s+name="_eventId"\s+value="([^"]*)"""") ?: "submit"
        val cllt = capture(page.text, """id="pwdLoginDiv"[\s\S]*?id="cllt"\s+name="cllt"\s+value="([^"]*)"""") ?: "userNameLogin"
        val dllt = capture(page.text, """id="dllt"\s+name="dllt"\s+value="([^"]*)"""") ?: "generalLogin"
        val fingerprintUrl = "${AUTH_URL}bfp/info".toHttpUrl().newBuilder()
            .addQueryParameter("bfp", browser.fingerprint).build()
        execute(accountClient, request(fingerprintUrl.toString())
            .header("X-Requested-With", "XMLHttpRequest").build()).requireSuccess("设置设备指纹失败")
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
        return execute(accountClient, loginRequest.newBuilder().post(form).build())
    }

    private suspend fun authenticateDevice(
        page: HttpResponse,
        loginRequest: Request,
        requestCode: suspend (String) -> String?
    ): HttpResponse {
        page.requireSuccess("获取设备认证页面失败")
        val service = loginRequest.url.queryParameter("service") ?: error("设备认证页面缺少服务地址")
        val userId = capture(page.text, """"reAuthUserId"\s*:\s*"([^"]*)"""") ?: error("设备认证页面缺少用户信息")
        val multifactor = capture(page.text, """"isMultifactor"\s*:\s*"([^"]*)"""") ?: error("设备认证页面缺少认证参数")
        val authType = capture(page.text, """"reAuthType"\s*:\s*"([^"]*)"""") ?: error("设备认证页面缺少认证方式")
        if (authType != "5") {
            val response = authPost("reAuthCheck/changeReAuthType.do", mapOf(
                "isMultifactor" to multifactor, "reAuthType" to "5", "service" to service
            ))
            val data = gson.fromJson(response.text, ReAuthTypeResponse::class.java)
            check(data?.code == 1) { data?.message ?: "选择风华宁大认证失败" }
        }
        val sent = authPost("dynamicCode/getDynamicCodeByReauth.do", mapOf(
            "userName" to userId, "authCodeTypeName" to "reAuthCpdailyDynamicCodeType"
        ))
        val codeResponse = gson.fromJson(sent.text, DynamicCodeResponse::class.java)
        check(codeResponse?.res in listOf("cpdaily_success", "other_success", "success", "code_time_fail")) {
            codeResponse?.returnMessage ?: "发送风华宁大验证码失败"
        }
        val code = requestCode(codeResponse?.returnMessage ?: "请查看风华宁大收到的验证码")
            ?: throw CancellationException("已取消登录")
        val submitted = authPost("reAuthCheck/reAuthSubmit.do", mapOf(
            "service" to service, "reAuthType" to "5", "isMultifactor" to multifactor,
            "dynamicCode" to code, "skipTmpReAuth" to "true"
        ))
        val authResponse = gson.fromJson(submitted.text, ReAuthResponse::class.java)
        check(!authResponse?.code.isNullOrBlank()) { "设备认证响应缺少结果" }
        check(authResponse?.code !in listOf("reAuth_failed", "reAuth_unauthorized")) {
            authResponse?.msg ?: "设备认证失败"
        }
        return execute(accountClient, loginRequest)
    }

    private suspend fun authPost(path: String, params: Map<String, String>): HttpResponse {
        val form = FormBody.Builder()
        params.forEach { (name, value) -> form.add(name, value) }
        val response = execute(accountClient, request(AUTH_URL + path)
            .header("X-Requested-With", "XMLHttpRequest").post(form.build()).build())
        response.requireSuccess("设备认证请求失败")
        return response
    }

    private suspend fun followRedirects(initial: HttpResponse): HttpResponse {
        var response = initial
        repeat(10) {
            if (!response.isRedirect) return response
            val target = response.redirectUrl()
            if (target.encodedPath.startsWith("/iap/")) throw SessionExpiredException()
            response = execute(accountClient, request(target.toString()).build())
        }
        check(!response.isRedirect) { "登录跳转次数过多" }
        return response
    }

    private suspend fun validateSession(jsessionId: String): Boolean {
        val response = execute(client, request(USER_INFO_URL, jsessionId).build())
        if (response.code !in 200..299) return false
        val info = try {
            gson.fromJson(response.text, UserInfoResponse::class.java)
        } catch (_: JsonSyntaxException) {
            return false
        }
        return info?.code == 20000 && info.success == true && info.data?.student?.xh == username
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

    suspend fun authorizeActivity(
        requestCode: suspend (String) -> String?
    ): Result<List<Cookie>> = result {
        requireNotNull(userManager.getDeviceId(username)) { "活动签到需要账号绑定的设备标识" }
        val password = requireNotNull(userManager.getUser(username)) { "请先完成账号登录" }.password
        ensurePlatformSession(password, requestCode)
        val entry = ACTIVITY_ENTRY.toHttpUrl()
        val page = followRedirects(execute(accountClient, request(ACTIVITY_ENTRY).build()))
        page.requireSuccess("活动授权失败")
        if (page.url.host != entry.host || page.url.encodedPath != entry.encodedPath ||
            cookies.loadForRequest(entry).none { it.name == "MOD_AUTH_CAS" }) {
            throw SessionExpiredException()
        }
        cookies.snapshot().filter { it.expiresAt > System.currentTimeMillis() }
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
                    continuation.resumeWithException(IOException(
                        "${request.method} ${request.url.host}${request.url.encodedPath}：${e.message}", e
                    ))
                }

                override fun onResponse(call: Call, response: Response) {
                    val data = try {
                        response.use {
                            val text = it.body?.string().orEmpty()
                            if (httpClient === accountClient && it.headers.values("Set-Cookie").isNotEmpty()) {
                                userManager.saveCookies(username, cookies.snapshot())
                            }
                            HttpResponse(it.code, it.request.url, it.header("Location"), text)
                        }
                    } catch (e: Exception) {
                        continuation.resumeWithException(if (e is IOException) IOException(
                            "${request.method} ${request.url.host}${request.url.encodedPath}：${e.message}", e
                        ) else e)
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
        val isRedirect: Boolean get() = code in listOf(301, 302, 303, 307, 308)

        fun redirectUrl(): HttpUrl = url.resolve(location ?: error("登录跳转缺少 Location")) ?: error("无法解析登录跳转地址")

        fun requireSuccess(message: String) {
            check(code in 200..299) { "$message（HTTP $code）" }
        }

        fun requireSession() {
            if (code == 401 || isRedirect || (code in 200..299 && text.trimStart().startsWith("<"))) {
                throw SessionExpiredException()
            }
        }
    }

    private data class Tenant(val id: String, val joinType: String, val idsUrl: String, val appId: String)
    private data class PlatformResponse(
        val errCode: String?,
        val code: String?,
        val status: String?,
        val errMsg: String?,
        val message: String?,
        val data: JsonElement?
    )
    private data class PlatformLoginResult(
        val status: String?,
        val authStatus: String?,
        val deviceStatus: String?,
        val deviceExceptionMsg: String?,
        val sessionToken: String?,
        val userId: String?,
        val tgc: String?,
        val tgcStatus: String?,
        val msgTip: String?
    )
    private data class ReAuthTypeResponse(val code: Int?, val message: String?)
    private data class DynamicCodeResponse(val res: String?, val returnMessage: String?)
    private data class ReAuthResponse(val code: String?, val msg: String?)
    private data class UserInfoResponse(val code: Int?, val success: Boolean?, val data: UserInfoData?)
    private data class UserInfoData(val student: Student?)
    private data class Student(val xh: String?)
    private data class SignResponse(val code: Int?, val msg: String?, val success: Boolean?)
}

class SessionExpiredException : IOException("登录状态已失效，请重新登录")
