package com.hartmann.usecase.port.out.internal.dto

data class SubmitPaymentResult(val paymentId: Long, val clientSecret: String)
