package com.hartmann.domain.value

import java.math.BigDecimal

data class Money(
    val amount: BigDecimal,
    val currency: Currency
) {
    init {
        require(amount > BigDecimal.ZERO) { "price must be positive" }
    }
}

enum class Currency {
    EUR, USD, GBP
}
