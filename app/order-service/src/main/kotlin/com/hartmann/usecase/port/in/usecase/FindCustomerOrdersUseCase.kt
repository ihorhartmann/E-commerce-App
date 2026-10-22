package com.hartmann.usecase.port.`in`.usecase

import com.hartmann.usecase.port.out.repository.dto.OrderPage
import java.util.UUID

interface FindCustomerOrdersUseCase {
    companion object {
        /** The most orders one-page holds. */
        const val MAX_PAGE_SIZE = 100
    }

    /**
     * Returns the page-th page (zero-based) of size orders placed by the customer, newest first - never anyone else's.
     * Rejects a negative page or a size outside 1..[MAX_PAGE_SIZE] with an InvalidPageException.
     */
    fun execute(customerId: UUID, page: Int, size: Int): OrderPage
}
