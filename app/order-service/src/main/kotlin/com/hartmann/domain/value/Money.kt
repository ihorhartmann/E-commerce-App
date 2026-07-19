package com.hartmann.domain.value

import com.hartmann.domain.exception.DomainException
import java.math.BigDecimal
import java.util.*

data class Money(
    val amount: BigDecimal,
    val currency: Currency
) {
    init {
        require(amount > BigDecimal.ZERO) { "price must be positive" }
    }
}

enum class Currency {
    EUR, USD, GBP;

    companion object {
        fun fromCode(code: String): Currency {
            try {
                return valueOf(code.uppercase(Locale.getDefault()))
            } catch (_: IllegalArgumentException) {
                throw DomainException("Unsupported currency: $code")
            }
        }
    }
}
