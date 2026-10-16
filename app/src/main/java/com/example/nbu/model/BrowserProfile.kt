package com.example.nbu.model

data class BrowserProfile(
    val userAgent: String,
    val fingerprint: String
) {
    val tabletUserAgent: String
        get() = userAgent.replace(" cpdaily/", " tablet cpdaily/")
}
