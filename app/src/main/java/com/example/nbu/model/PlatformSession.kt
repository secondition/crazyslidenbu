package com.example.nbu.model

data class PlatformSession(
    val sessionToken: String,
    val userId: String,
    val tgc: String,
    val idsToken: String
)
