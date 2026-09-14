package com.hartmann.interfaceadapter.`in`.http.idempotency.dto

data class RequestFingerprint(val method: String, val path: String, val body: ByteArray)
