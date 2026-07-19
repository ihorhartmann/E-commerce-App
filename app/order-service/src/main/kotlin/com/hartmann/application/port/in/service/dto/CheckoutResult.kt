package com.hartmann.application.port.`in`.service.dto

import com.hartmann.domain.model.Order

data class CheckoutResult(val order: Order, val clientSecret: String)