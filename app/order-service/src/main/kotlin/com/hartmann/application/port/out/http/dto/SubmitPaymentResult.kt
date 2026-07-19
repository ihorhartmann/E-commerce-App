package com.hartmann.application.port.out.http.dto

data class SubmitPaymentResult(val paymentId: Long, val clientSecret: String)
