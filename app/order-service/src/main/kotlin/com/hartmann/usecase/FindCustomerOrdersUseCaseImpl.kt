package com.hartmann.usecase

import com.hartmann.usecase.port.`in`.usecase.FindCustomerOrdersUseCase
import com.hartmann.usecase.port.`in`.usecase.FindCustomerOrdersUseCase.Companion.MAX_PAGE_SIZE
import com.hartmann.usecase.port.`in`.usecase.exception.InvalidPageException
import com.hartmann.usecase.port.out.repository.OrderRepository
import com.hartmann.usecase.port.out.repository.dto.OrderPage
import org.springframework.stereotype.Service
import java.util.UUID

@Service
class FindCustomerOrdersUseCaseImpl(
    private val orderRepository: OrderRepository,
) : FindCustomerOrdersUseCase {

    override fun execute(customerId: UUID, page: Int, size: Int): OrderPage {
        if (page < 0) {
            throw InvalidPageException(page, size, "page must not be negative")
        }
        if (size !in 1..MAX_PAGE_SIZE) {
            throw InvalidPageException(page, size, "size must be between 1 and $MAX_PAGE_SIZE")
        }

        return orderRepository.findCustomerOrders(customerId, page, size)
    }
}
