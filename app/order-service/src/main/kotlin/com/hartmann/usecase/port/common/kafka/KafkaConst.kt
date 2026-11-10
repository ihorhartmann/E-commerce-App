package com.hartmann.usecase.port.common.kafka

object KafkaConst {
    const val MESSAGE_TYPE = "messageType"
    const val CORRELATION_ID = "correlationId"
    const val MESSAGE_ID = "messageId"

    const val INVENTORY_COMMANDS_TOPIC = "inventory.commands"
    const val SHIPPING_COMMANDS_TOPIC = "shipping.commands"
    const val PAYMENT_COMMANDS_TOPIC = "payment.commands"
    const val PAYMENT_EVENTS_TOPIC = "payment.events"
    const val SHIPPING_EVENTS_TOPIC = "shipping.events"

    // Dead-letter topics: one per consumed topic
    const val DLT_SUFFIX = ".DLT"
    const val PAYMENT_EVENTS_DLT_TOPIC = PAYMENT_EVENTS_TOPIC + DLT_SUFFIX
    const val SHIPPING_EVENTS_DLT_TOPIC = SHIPPING_EVENTS_TOPIC + DLT_SUFFIX

    const val CANCEL_RESERVATION_COMMAND = "CancelReservation"
    const val FULFILL_RESERVATION_COMMAND = "FulfillReservation"
    const val HOLD_RESERVATION_COMMAND = "HoldReservation"
    const val RESTOCK_COMMAND = "Restock"
    const val CREATE_SHIPMENT_COMMAND = "CreateShipment"
    const val CANCEL_SHIPMENT_COMMAND = "CancelShipment"
    const val REFUND_REQUESTED_COMMAND = "RefundRequested"

    const val PAYMENT_COMPLETED_EVENT = "PaymentCompleted"
    const val PAYMENT_FAILED_EVENT = "PaymentFailed"
    const val PAYMENT_EXPIRED_EVENT = "PaymentExpired"
    const val PAYMENT_CANCELLED_EVENT = "PaymentCancelled"
    const val REFUND_COMPLETED_EVENT = "RefundCompleted"
    const val REFUND_FAILED_EVENT = "RefundFailed"

    const val SHIPMENT_CREATED_EVENT = "ShipmentCreated"
    const val SHIPMENT_IN_DELIVERY_EVENT = "ShipmentInDelivery"
    const val SHIPMENT_DELIVERED_EVENT = "ShipmentDelivered"
    const val SHIPMENT_CANCELLED_EVENT = "ShipmentCancelled"
    const val SHIPMENT_LOST_EVENT = "ShipmentLost"
    const val SHIPMENT_RETURNED_TO_INVENTORY_EVENT = "ShipmentReturnedToInventory"
}
