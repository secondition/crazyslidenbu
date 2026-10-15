package com.example.nbu.model

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

data class ActivityCode(
    val uuid: String,
    val taskWid: String,
    val wid: String,
    val toolCode: String,
    val signType: String
) {
    val isSignin: Boolean get() = signType == "in"

    companion object {
        const val BASE_URL = "https://nbu.campusphere.net/wec-activity-user-apps"

        fun parse(value: String): ActivityCode {
            val scanned = requireNotNull(value.toHttpUrlOrNull()) { "请扫描趣活动签到二维码" }
            val embedded = scanned.queryParameter("openUrl")?.takeIf { it.isNotBlank() }
            val url = if (embedded == null) scanned else requireNotNull(embedded.toHttpUrlOrNull()) { "二维码链接无效" }
            val base = BASE_URL.toHttpUrl()
            require(url.scheme == base.scheme && url.host == base.host && url.port == base.port && url.encodedPath in listOf(
                "${base.encodedPath}/mobile/entry-scan/index.html",
                "${base.encodedPath}/mobile/new-entry-scan/index.html"
            )) { "请扫描趣活动签到二维码" }
            val query = listOfNotNull(
                url.encodedQuery,
                url.encodedFragment?.substringAfter('?', "")?.takeIf { it.isNotEmpty() }
            ).joinToString("&")
            val params = url.newBuilder().encodedQuery(query).fragment(null).build()
            fun required(name: String): String = requireNotNull(params.queryParameterValues(name).lastOrNull()?.takeIf { it.isNotBlank() }) {
                "二维码缺少 $name"
            }
            val code = ActivityCode(required("uuid"), required("taskWid"), required("wid"), required("toolCode"), required("signType"))
            require(code.toolCode in listOf("1", "9")) { "此二维码不属于活动签到或打卡" }
            require(code.signType in listOf("in", "out")) { "二维码签到方式无效" }
            return code
        }
    }
}
