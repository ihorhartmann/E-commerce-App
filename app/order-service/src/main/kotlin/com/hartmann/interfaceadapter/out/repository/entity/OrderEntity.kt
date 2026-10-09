package com.hartmann.interfaceadapter.out.repository.entity

import com.hartmann.entities.model.CancellationReason
import com.hartmann.entities.model.Order
import com.hartmann.entities.model.OrderStatus
import com.hartmann.entities.value.Address
import com.hartmann.entities.value.Currency
import com.hartmann.entities.value.Money
import com.hartmann.entities.value.RefundInfo
import com.hartmann.entities.value.ShipmentInfo
import jakarta.persistence.CascadeType
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.OneToMany
import jakarta.persistence.OrderBy
import jakarta.persistence.Table
import jakarta.persistence.Version
import org.hibernate.annotations.BatchSize
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "orders", schema = "orders")
class OrderEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long = 0,

    var customerId: UUID = UUID(0, 0),

    // a page of orders loads its items in batches, not with one query per order
    @BatchSize(size = 100)
    @OneToMany(mappedBy = "order", cascade = [CascadeType.ALL], orphanRemoval = true, fetch = FetchType.EAGER)
    @OrderBy("id ASC")
    var items: MutableList<OrderItemEntity> = mutableListOf(),

    var addressCity: String = "",
    var addressPostalCode: String = "",
    var addressLine1: String = "",
    var addressLine2: String = "",

    @Enumerated(EnumType.STRING)
    var status: OrderStatus = OrderStatus.PLACED,

    var paymentConfirmedAt: Instant? = null,

    @Enumerated(EnumType.STRING)
    var cancellationReason: CancellationReason? = null,

    var awaitingReturn: Boolean = false,

    var shipmentTrackingNumber: String? = null,
    var shipmentPickedUpAt: Instant? = null,
    var shipmentDeliveredAt: Instant? = null,
    var shipmentReturnedAt: Instant? = null,

    var refundRequestedAt: Instant? = null,
    var refundAmount: BigDecimal? = null,

    @Enumerated(EnumType.STRING)
    var refundCurrency: Currency? = null,
    var refundConfirmedAt: Instant? = null,
    var refundFailedAt: Instant? = null,

    @Version
    var version: Long = 0,

    var createdAt: Instant = Instant.now(),
    var updatedAt: Instant? = null,
)

fun toOrderEntity(order: Order): OrderEntity {
    val entity = OrderEntity(
        id = order.id,
        customerId = order.customerId,
        addressCity = order.address.city,
        addressPostalCode = order.address.postalCode,
        addressLine1 = order.address.line1,
        addressLine2 = order.address.line2,
        status = order.status,
        paymentConfirmedAt = order.paymentConfirmedAt,
        cancellationReason = order.cancellationReason,
        awaitingReturn = order.awaitingReturn,
        shipmentTrackingNumber = order.shipment?.trackingNumber,
        shipmentPickedUpAt = order.shipment?.pickedUpAt,
        shipmentDeliveredAt = order.shipment?.deliveredAt,
        shipmentReturnedAt = order.shipment?.returnedAt,
        refundRequestedAt = order.refund?.requestedAt,
        refundAmount = order.refund?.amount?.amount,
        refundCurrency = order.refund?.amount?.currency,
        refundConfirmedAt = order.refund?.confirmedAt,
        refundFailedAt = order.refund?.failedAt,
        version = order.version,
        createdAt = order.createdAt,
        updatedAt = order.updatedAt,
    )
    entity.items = order.items.map { toOrderItemEntity(it, entity) }.toMutableList()
    return entity
}

fun fromOrderEntity(entity: OrderEntity): Order {
    val shipment = entity.shipmentTrackingNumber?.let {
        ShipmentInfo(
            trackingNumber = it,
            pickedUpAt = entity.shipmentPickedUpAt,
            deliveredAt = entity.shipmentDeliveredAt,
            returnedAt = entity.shipmentReturnedAt,
        )
    }
    val refund = entity.refundRequestedAt?.let {
        RefundInfo(
            requestedAt = it,
            amount = entity.refundAmount?.let { amount -> Money(amount, requireNotNull(entity.refundCurrency)) },
            confirmedAt = entity.refundConfirmedAt,
            failedAt = entity.refundFailedAt,
        )
    }

    return Order.createExisting(
        id = entity.id,
        customerId = entity.customerId,
        items = entity.items.map(::fromOrderItemEntity),
        address = Address(entity.addressCity, entity.addressPostalCode, entity.addressLine1, entity.addressLine2),
        status = entity.status,
        paymentConfirmedAt = entity.paymentConfirmedAt,
        shipment = shipment,
        cancellationReason = entity.cancellationReason,
        awaitingReturn = entity.awaitingReturn,
        refund = refund,
        version = entity.version,
        createdAt = entity.createdAt,
        updatedAt = entity.updatedAt,
    )
}
