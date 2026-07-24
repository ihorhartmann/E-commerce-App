package com.hartmann.entities.value

import java.time.LocalDateTime

data class RefundInfo(
    val requestedAt: LocalDateTime,
    val amount: Money? = null,
    val confirmedAt: LocalDateTime? = null,
    val failedAt: LocalDateTime? = null,
) {
    val isConfirmed: Boolean get() = confirmedAt != null

    fun confirm(amount: Money): RefundInfo {
        check(confirmedAt == null) { "refund already confirmed" }
        return copy(amount = amount, confirmedAt = LocalDateTime.now())
    }

    fun markFailed(): RefundInfo {
        check(confirmedAt == null) { "cannot fail a refund that already confirmed" }
        return copy(failedAt = LocalDateTime.now())
    }

    companion object {
        fun request() = RefundInfo(requestedAt = LocalDateTime.now())
    }
}
