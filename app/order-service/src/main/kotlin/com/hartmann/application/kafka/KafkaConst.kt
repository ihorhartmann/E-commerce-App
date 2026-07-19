package com.hartmann.application.kafka

object KafkaConst {
    const val EVENT_TYPE = "eventType"
    const val CORRELATION_ID = "correlationId"
    const val EVENT_ID = "eventId"

    const val INVENTORY_COMMANDS = "inventory.commands"
    const val SHIPPING_COMMANDS = "shipping.commands"
    const val PAYMENT_COMMANDS = "payment.commands"
    const val PAYMENT_EVENTS = "payment.events"
    const val SHIPPING_EVENTS = "shipping.events"

    const val CANCEL_RESERVATION_EVENT_TYPE = "CancelReservation"
    const val FULFILL_RESERVATION_EVENT_TYPE = "FulfillReservation"
    const val HOLD_RESERVATION_EVENT_TYPE = "HoldReservation"
    const val RESTOCK_EVENT_TYPE = "Restock"
    const val CREATE_SHIPMENT_EVENT_TYPE = "CreateShipment"
    const val REFUND_REQUESTED_EVENT_TYPE = "RefundRequested"

    const val PAYMENT_COMPLETED_EVENT_TYPE = "PaymentCompleted"
    const val PAYMENT_FAILED_EVENT_TYPE = "PaymentFailed"
    const val REFUND_COMPLETED_EVENT_TYPE = "RefundCompleted"
    const val REFUND_FAILED_EVENT_TYPE = "RefundFailed"
    const val SHIPMENT_CREATION_FAILED_EVENT_TYPE = "ShipmentCreationFailed"

    const val SHIPMENT_CREATED_EVENT_TYPE = "ShipmentCreated"
    const val SHIPMENT_IN_DELIVERY_EVENT_TYPE = "ShipmentInDelivery"
    const val SHIPMENT_DELIVERED_EVENT_TYPE = "ShipmentDelivered"
    const val SHIPMENT_CANCELLED_EVENT_TYPE = "ShipmentCancelled"
    const val SHIPMENT_LOST_EVENT_TYPE = "ShipmentLost"
    const val SHIPMENT_RETURNED_TO_INVENTORY_EVENT_TYPE = "ShipmentReturnedToInventory"
}
