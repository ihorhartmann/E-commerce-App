package com.hartmann.usecase.port.`in`.usecase.dto

import com.hartmann.entities.model.Order

data class CheckoutResult(val order: Order, val clientSecret: String)