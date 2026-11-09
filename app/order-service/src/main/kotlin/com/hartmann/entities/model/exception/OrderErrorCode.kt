package com.hartmann.entities.model.exception

import com.hartmann.entities.exception.ErrorCode

/** Codes of the rules the order itself enforces. */
enum class OrderErrorCode(override val title: String) : ErrorCode {
    ORDER_NOT_CANCELLABLE("Order can't be cancelled in its current status"),
    ORDER_TRANSITION_NOT_ALLOWED("Order can't make this transition in its current status"),
    SHIPMENT_NOT_CREATED("Order has no shipment yet"),
    REFUND_NOT_REQUESTED("Order has no refund request"),
    RETURN_NOT_AWAITED("Order awaits no returned goods"),
    UNREQUESTED_SHIPMENT_CANCELLATION("Shipment was cancelled for an order nobody asked to cancel"),
    REFUND_AMOUNT_MISMATCH("Refunded amount differs from the requested one"),
    INCONSISTENT_ORDER("Internal server error"),
    ORDER_INVARIANT_VIOLATED("Internal server error"),
}
