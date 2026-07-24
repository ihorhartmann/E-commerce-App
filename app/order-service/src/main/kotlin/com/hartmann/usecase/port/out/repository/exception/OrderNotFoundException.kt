package com.hartmann.usecase.port.out.repository.exception

class OrderNotFoundException(orderId: Long) : NoSuchElementException("Order [ID=$orderId] was not found")
