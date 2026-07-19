package com.hartmann.interfaces.http

import com.hartmann.application.port.out.http.exception.InsufficientStockException
import com.hartmann.application.port.out.repository.exception.OrderNotFoundException
import com.hartmann.domain.exception.DomainException
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

@RestControllerAdvice
class GlobalExceptionHandler {
    companion object {
        private val LOG: Logger = LoggerFactory.getLogger(GlobalExceptionHandler::class.java)
    }

    @ExceptionHandler(OrderNotFoundException::class)
    fun notFound(exception: OrderNotFoundException) =
        error(HttpStatus.NOT_FOUND, exception.message ?: "Order not found")

    @ExceptionHandler(InsufficientStockException::class)
    fun insufficientStock(exception: InsufficientStockException) =
        error(HttpStatus.CONFLICT, exception.message ?: "Insufficient stock")

    @ExceptionHandler(DomainException::class, IllegalArgumentException::class)
    fun invalid(exception: RuntimeException) = error(HttpStatus.BAD_REQUEST, exception.message ?: "Invalid request")

    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun invalidRequest(exception: MethodArgumentNotValidException): ResponseEntity<Map<String, Any>> =
        ResponseEntity.badRequest().body(
            mapOf(
                "status" to HttpStatus.BAD_REQUEST,
                "error" to "Validation failed",
                "details" to exception.bindingResult.fieldErrors.map { it.field to (it.defaultMessage ?: "invalid") }
                    .toMap()
            )
        )

    @ExceptionHandler(Exception::class)
    fun internalError(exception: RuntimeException): ResponseEntity<Map<String, Any>> {
        LOG.error(exception.message, exception)
        return error(HttpStatus.INTERNAL_SERVER_ERROR,  "Internal server error")
    }


    private fun error(status: HttpStatus, message: String): ResponseEntity<Map<String, Any>> =
        ResponseEntity.status(status).body(mapOf("status" to status.value(), "error" to message))
}
