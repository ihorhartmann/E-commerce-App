package com.hartmann.entities.value

import com.hartmann.entities.value.exception.InvalidMoneyAmountException
import com.hartmann.entities.value.exception.UnsupportedCurrencyException
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
    } catch (e: ArithmeticException) {
        throw InvalidMoneyAmountException(amount, e)
    }

    init {
        if (this.amount < BigDecimal.ZERO) {
            throw InvalidMoneyAmountException(amount)
        }
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
            } catch (e: IllegalArgumentException) {
                throw UnsupportedCurrencyException(code, e)
            }
        }
    }
}
