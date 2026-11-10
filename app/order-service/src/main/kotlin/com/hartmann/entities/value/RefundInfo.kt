package com.hartmann.entities.value

import com.hartmann.entities.value.exception.RefundTransitionNotAllowedException
import java.time.Instant

data class RefundInfo(
    val requestedAt: Instant,
    val amount: Money? = null,
    val confirmedAt: Instant? = null,
    val failedAt: Instant? = null,
) {
    val isConfirmed: Boolean get() = confirmedAt != null

    fun confirm(amount: Money): RefundInfo {
        requireNotConfirmed("confirm")
        return copy(amount = amount, confirmedAt = Instant.now())
    }

    fun fail(): RefundInfo {
        requireNotConfirmed("fail")
        return copy(failedAt = Instant.now())
    }

    private fun requireNotConfirmed(action: String) {
        if (isConfirmed) {
            throw RefundTransitionNotAllowedException(action)
        }
    }

    companion object {
        fun request() = RefundInfo(requestedAt = Instant.now())
    }
}
