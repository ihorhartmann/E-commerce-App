package com.hartmann.usecase.port.out.repository.exception

import com.hartmann.entities.exception.NotFoundException

class OrderNotFoundException(val orderId: Long) :
    NotFoundException(RepositoryErrorCode.ORDER_NOT_FOUND, "Order not found", mapOf("orderId" to orderId))
