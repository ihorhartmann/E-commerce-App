package com.hartmann.application.port.out.repository.exception

class OrderNotFoundException(orderId: Long) : NoSuchElementException("Order [ID=$orderId] was not found")
