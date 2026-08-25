package com.hartmann.interfaceadapter.out.repository

import com.hartmann.entities.model.Order
import com.hartmann.interfaceadapter.out.repository.entity.fromOrderEntity
import com.hartmann.interfaceadapter.out.repository.entity.toOrderEntity
import com.hartmann.usecase.port.out.repository.OrderRepository
import com.hartmann.usecase.port.out.repository.exception.OrderNotFoundException
import org.springframework.stereotype.Repository

@Repository
class PostgresOrderRepository(private val jpaRepository: OrderJpaRepository) : OrderRepository {
    override fun save(order: Order): Order {
        val saved = jpaRepository.save(toOrderEntity(order))
        return fromOrderEntity(saved)
    }

    override fun getOrder(id: Long): Order {
        val entity = jpaRepository.findById(id).orElseThrow { OrderNotFoundException(id) }
        return fromOrderEntity(entity)
    }
}
