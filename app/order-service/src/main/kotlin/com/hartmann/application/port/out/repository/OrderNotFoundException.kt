package com.hartmann.application.port.out.repository

class OrderNotFoundException(orderId: Long) : NoSuchElementException("Order [ID=$orderId] was not found")
