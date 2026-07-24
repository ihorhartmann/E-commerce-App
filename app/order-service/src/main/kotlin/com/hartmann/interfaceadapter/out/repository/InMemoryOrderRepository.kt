package com.hartmann.interfaceadapter.out.repository

import com.hartmann.usecase.port.out.repository.OrderRepository
import com.hartmann.usecase.port.out.repository.exception.OrderNotFoundException
import com.hartmann.entities.model.Order
import com.hartmann.entities.model.OrderItem
import com.hartmann.entities.value.Address
import com.hartmann.entities.value.Currency
import com.hartmann.entities.value.Money
import org.springframework.stereotype.Repository
import java.math.BigDecimal
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random

@Repository
class InMemoryOrderRepository : OrderRepository {
    private val orders = ConcurrentHashMap<Long, Order>()
    private val orderSequence = AtomicLong(Random.nextLong(10, 100))
    private val itemSequence = AtomicLong()

    init {
        listOf(
            Order.create(
                userId = 1,
                items = listOf(OrderItem(0, 0, 101, 2u, Money(BigDecimal("19.99"), Currency.EUR))),
                address = Address("Kyiv", "01001", "Khreshchatyk St, 1", ""),
            ),
            Order.create(
                userId = 2,
                items = listOf(
                    OrderItem(0, 0, 102, 1u, Money(BigDecimal("49.50"), Currency.EUR)),
                    OrderItem(0, 0, 103, 3u, Money(BigDecimal("7.25"), Currency.EUR)),
                ),
                address = Address("Lviv", "79000", "Svobody Ave, 10", "Apt 5"),
            ),
            Order.create(
                userId = 3,
                items = listOf(OrderItem(0, 0, 104, 1u, Money(BigDecimal("99.00"), Currency.USD))),
                address = Address("Warsaw", "00-001", "Marszalkowska St, 20", ""),
            ),
        ).forEach(::save)
    }

    override fun save(order: Order): Order {
        val storedOrder = if (order.id == 0L) {
            val orderId = orderSequence.incrementAndGet()
            order.copy(
                id = orderId,
                items = order.items.map { item ->
                    item.copy(id = itemSequence.incrementAndGet(), orderId = orderId)
                },
            )
        } else {
            order
        }

        orders[storedOrder.id] = storedOrder
        return storedOrder
    }

    override fun getOrder(id: Long): Order = orders[id] ?: throw OrderNotFoundException(id)
}
