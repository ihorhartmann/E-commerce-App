package com.hartmann.interfaceadapter.`in`.kafka.dto

// Shared shape for the payment events that carry nothing beyond which order they're about
// (PaymentCompleted, PaymentFailed, PaymentExpired, RefundFailed)
data class OrderIdEvent(val orderId: Long)
