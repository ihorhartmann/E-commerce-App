package com.hartmann.application.port.`in`.saga

import com.hartmann.application.saga.event.PaymentCompleted
import com.hartmann.application.saga.event.PaymentFailed
import com.hartmann.application.saga.event.ShipmentCreated
import com.hartmann.application.saga.event.ShipmentFailed
import com.hartmann.application.saga.event.StockRejected
import com.hartmann.application.saga.event.StockReserved

interface FulfillOrderSaga {
    fun onStockReserved(event: StockReserved)
    fun onStockRejected(event: StockRejected)
    fun onPaymentCompleted(event: PaymentCompleted)
    fun onPaymentFailed(event: PaymentFailed)
    fun onShipmentCreated(event: ShipmentCreated)
    fun onShipmentFailed(event: ShipmentFailed)
}
