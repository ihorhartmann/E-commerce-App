package com.hartmann.core.model

/**
 * An amount in minor units (cents), so that sums stay exact: JS has no decimal type, and floating point would turn
 * 0.10 + 0.20 into 0.30000000000000004.
 */
data class Money(val minorUnits: Long, val currency: String) {
    operator fun times(quantity: Int): Money = Money(minorUnits * quantity, currency)

    operator fun plus(other: Money): Money {
        require(currency == other.currency) { "Can't add $currency and ${other.currency}" }
        return Money(minorUnits + other.minorUnits, currency)
    }

    /** The amount as the API takes it, e.g. `79.00`. */
    fun toDecimalString(): String {
        val sign = if (minorUnits < 0) "-" else ""
        val absolute = if (minorUnits < 0) -minorUnits else minorUnits
        val fraction = (absolute % MINOR_UNITS_PER_UNIT).toString().padStart(FRACTION_DIGITS, '0')
        return "$sign${absolute / MINOR_UNITS_PER_UNIT}.$fraction"
    }

    fun format(): String = "${toDecimalString()} $currency"

    companion object {
        private const val FRACTION_DIGITS = 2
        private const val MINOR_UNITS_PER_UNIT = 100L
        private val DECIMAL = Regex("""^(-?)(\d+)(?:\.(\d{1,2}))?$""")

        /** Parses a decimal amount such as `79`, `79.5` or `79.00`; null if it isn't one, or has more than 2 decimals. */
        fun parse(amount: String, currency: String): Money? {
            val match = DECIMAL.matchEntire(amount.trim()) ?: return null
            val (sign, units, fraction) = match.destructured
            val minorUnits = units.toLong() * MINOR_UNITS_PER_UNIT + fraction.padEnd(FRACTION_DIGITS, '0').toLong()
            return Money(if (sign == "-") -minorUnits else minorUnits, currency)
        }
    }
}
