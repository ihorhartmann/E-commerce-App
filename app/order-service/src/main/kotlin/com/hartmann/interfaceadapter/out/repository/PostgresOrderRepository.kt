package com.hartmann.interfaceadapter.out.repository

import com.hartmann.entities.model.Order
import com.hartmann.interfaceadapter.out.repository.entity.fromOrderEntity
import com.hartmann.interfaceadapter.out.repository.entity.toOrderEntity
import com.hartmann.usecase.port.out.repository.OrderRepository
import com.hartmann.usecase.port.out.repository.dto.OrderPage
import com.hartmann.usecase.port.out.repository.exception.ConcurrentUpdateException
import com.hartmann.usecase.port.out.repository.exception.OrderNotFoundException
import org.springframework.dao.OptimisticLockingFailureException
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
class PostgresOrderRepository(private val jpaRepository: OrderJpaRepository) : OrderRepository {
    // Flushed right away, so a stale version fails here - as the port's ConcurrentUpdateException -
    // rather than later at commit, as a Spring exception the use cases don't know about.
    override fun save(order: Order): Order =
        try {
            fromOrderEntity(jpaRepository.saveAndFlush(toOrderEntity(order)))
        } catch (e: OptimisticLockingFailureException) {
            throw ConcurrentUpdateException("Order [ID=${order.id}] was changed concurrently since version ${order.version}", e)
        }

    override fun getOrder(id: Long): Order {
        val entity = jpaRepository.findById(id).orElseThrow { OrderNotFoundException(id) }
        return fromOrderEntity(entity)
    }

    override fun findCustomerOrders(customerId: UUID, page: Int, size: Int): OrderPage {
        val pageRequest = PageRequest.of(page, size, Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id")))
        val entities = jpaRepository.findCustomerOrders(customerId, pageRequest)
        return OrderPage(entities.content.map(::fromOrderEntity), entities.totalElements)
    }
}
