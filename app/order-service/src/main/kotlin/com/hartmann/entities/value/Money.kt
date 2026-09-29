package com.hartmann.entities.value

import com.hartmann.entities.exception.DomainException
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.*

/**
 * A money amount, always stored with exactly [SCALE] decimal places, so two amounts are equal
 * exactly when they are numerically equal (20 becomes 20.00). An amount with more decimal places
 * is rejected rather than rounded.
 */
class Money(amount: BigDecimal, val currency: Currency) {
    val amount: BigDecimal = try {
        amount.setScale(SCALE, RoundingMode.UNNECESSARY)
    } catch (_: ArithmeticException) {
        throw IllegalArgumentException("amount $amount must not have more than $SCALE decimal places")
    }

    init {
        require(this.amount >= BigDecimal.ZERO) { "amount must not be negative" }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Money) return false
        return amount == other.amount && currency == other.currency
    }

    override fun hashCode(): Int = 31 * amount.hashCode() + currency.hashCode()

    override fun toString(): String = "Money(amount=$amount, currency=$currency)"

    private companion object {
        const val SCALE = 2
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
