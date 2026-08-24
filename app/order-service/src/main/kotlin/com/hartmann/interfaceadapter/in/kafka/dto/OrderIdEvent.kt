package com.hartmann.interfaceadapter.`in`.kafka.dto

// Shared shape for the payment/shipping events that carry nothing beyond which order they're
// about (PaymentCompleted, PaymentFailed, ShipmentCreationFailed, RefundFailed)
data class OrderIdEvent(val orderId: Long)
