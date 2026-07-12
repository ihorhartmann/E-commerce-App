package com.hartmann.interfaces.http

import com.hartmann.application.port.out.repository.OrderNotFoundException
import com.hartmann.domain.exception.DomainValidationException
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

@RestControllerAdvice
class GlobalExceptionHandler {
    @ExceptionHandler(OrderNotFoundException::class)
    fun notFound(exception: OrderNotFoundException) = error(HttpStatus.NOT_FOUND, exception.message ?: "Order not found")

    @ExceptionHandler(DomainValidationException::class, IllegalArgumentException::class)
    fun invalid(exception: RuntimeException) = error(HttpStatus.BAD_REQUEST, exception.message ?: "Invalid request")

    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun invalidRequest(exception: MethodArgumentNotValidException): ResponseEntity<Map<String, Any>> =
        ResponseEntity.badRequest().body(mapOf("status" to HttpStatus.BAD_REQUEST, "error" to "Validation failed", "details" to exception.bindingResult.fieldErrors.map { it.field to (it.defaultMessage ?: "invalid") }.toMap()))

    private fun error(status: HttpStatus, message: String): ResponseEntity<Map<String, Any>> =
        ResponseEntity.status(status).body(mapOf("status" to status.value(), "error" to message))
}
