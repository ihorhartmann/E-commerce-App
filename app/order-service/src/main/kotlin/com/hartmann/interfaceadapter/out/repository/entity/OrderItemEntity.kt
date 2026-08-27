package com.hartmann.interfaceadapter.out.repository.entity

import com.hartmann.entities.model.OrderItem
import com.hartmann.entities.value.Currency
import com.hartmann.entities.value.Money
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table
import java.math.BigDecimal

@Entity
@Table(name = "order_items", schema = "orders")
class OrderItemEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long = 0,

    @ManyToOne
    @JoinColumn(name = "order_id", nullable = false)
    var order: OrderEntity? = null,

    var productId: Long = 0,

    var quantity: Int = 0,

    var priceAmount: BigDecimal = BigDecimal.ZERO,

    @Enumerated(EnumType.STRING)
    var priceCurrency: Currency = Currency.USD,
)

fun toOrderItemEntity(item: OrderItem, order: OrderEntity): OrderItemEntity {
    return OrderItemEntity(
        id = item.id,
        order = order,
        productId = item.productId,
        quantity = item.quantity,
        priceAmount = item.price.amount,
        priceCurrency = item.price.currency,
    )
}

fun fromOrderItemEntity(entity: OrderItemEntity): OrderItem {
    return OrderItem.createExisting(
        id = entity.id,
        orderId = requireNotNull(entity.order) { "order_items.${entity.id} has no order" }.id,
        productId = entity.productId,
        quantity = entity.quantity,
        price = Money(entity.priceAmount, entity.priceCurrency),
    )
}
