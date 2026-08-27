package com.hartmann.entities.value

import java.time.Instant

data class RefundInfo(
    val requestedAt: Instant,
    val amount: Money? = null,
    val confirmedAt: Instant? = null,
    val failedAt: Instant? = null,
) {
    val isConfirmed: Boolean get() = confirmedAt != null

    fun confirm(amount: Money): RefundInfo {
        check(confirmedAt == null) { "refund already confirmed" }
        return copy(amount = amount, confirmedAt = Instant.now())
    }

    fun markFailed(): RefundInfo {
        check(confirmedAt == null) { "cannot fail a refund that already confirmed" }
        return copy(failedAt = Instant.now())
    }

    companion object {
        fun request() = RefundInfo(requestedAt = Instant.now())
    }
}
