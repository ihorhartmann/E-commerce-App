package com.hartmann.usecase.port.out.http.dto

data class SubmitPaymentResult(val paymentId: Long, val clientSecret: String)
