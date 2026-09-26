package com.hartmann.usecase.port.out.internal.exception

class PaymentGatewayUnavailableException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
