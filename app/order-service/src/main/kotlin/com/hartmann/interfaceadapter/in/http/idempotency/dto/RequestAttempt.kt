package com.hartmann.interfaceadapter.`in`.http.idempotency.dto

import com.hartmann.usecase.port.out.idempotency.dto.HttpRequestRecord

data class RequestAttempt(
    val requestResult: RequestResult,
    val record: HttpRequestRecord? = null,
) {
    companion object {
        fun claimed() = RequestAttempt(RequestResult.CLAIMED)
    }
}
