package com.hartmann.interfaceadapter.out.repository

import com.hartmann.interfaceadapter.out.repository.entity.OrderEntity
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface OrderJpaRepository : JpaRepository<OrderEntity, Long> {
    fun findCustomerOrders(customerId: UUID, pageable: Pageable): Page<OrderEntity>
}
