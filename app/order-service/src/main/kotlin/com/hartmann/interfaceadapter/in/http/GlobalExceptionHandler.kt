package com.hartmann.interfaceadapter.`in`.http

import com.hartmann.usecase.port.out.internal.exception.InsufficientStockException
import com.hartmann.usecase.port.out.internal.exception.InventoryUnavailableException
import com.hartmann.usecase.port.out.internal.exception.PaymentGatewayException
import com.hartmann.usecase.port.out.internal.exception.PaymentGatewayUnavailableException
import com.hartmann.usecase.port.out.internal.exception.PaymentServiceUnavailableException
import com.hartmann.usecase.port.out.repository.exception.ConcurrentUpdateException
import com.hartmann.usecase.port.out.repository.exception.OrderNotFoundException
import com.hartmann.entities.exception.DomainException
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException

@RestControllerAdvice
class GlobalExceptionHandler {
    companion object {
        private val LOG: Logger = LoggerFactory.getLogger(GlobalExceptionHandler::class.java)
    }

    @ExceptionHandler(OrderNotFoundException::class)
    fun notFound(exception: OrderNotFoundException) =
        error(HttpStatus.NOT_FOUND, exception.message ?: "Order not found")

    // 503, not 409: the conflict is transient - repeating the request reloads the order and usually
    // succeeds - and the idempotency filter would replay a cached 4xx instead of running it again.
    @ExceptionHandler(ConcurrentUpdateException::class)
    fun concurrentUpdate(exception: ConcurrentUpdateException) =
        error(HttpStatus.SERVICE_UNAVAILABLE, exception.message ?: "Order was changed concurrently")

    @ExceptionHandler(InsufficientStockException::class)
    fun insufficientStock(exception: InsufficientStockException) =
        error(HttpStatus.CONFLICT, exception.message ?: "Insufficient stock")

    @ExceptionHandler(InventoryUnavailableException::class)
    fun inventoryUnavailable(exception: InventoryUnavailableException) =
        error(HttpStatus.SERVICE_UNAVAILABLE, exception.message ?: "Inventory service temporarily unavailable")

    @ExceptionHandler(PaymentGatewayException::class)
    fun paymentGatewayRejected(exception: PaymentGatewayException) =
        error(HttpStatus.CONFLICT, exception.message ?: "Payment gateway rejected the payment")

    @ExceptionHandler(PaymentGatewayUnavailableException::class)
    fun paymentGatewayUnavailable(exception: PaymentGatewayUnavailableException) =
        error(HttpStatus.SERVICE_UNAVAILABLE, exception.message ?: "Payment gateway temporarily unavailable")

    @ExceptionHandler(PaymentServiceUnavailableException::class)
    fun paymentServiceUnavailable(exception: PaymentServiceUnavailableException) =
        error(HttpStatus.SERVICE_UNAVAILABLE, exception.message ?: "Payment service temporarily unavailable")

    @ExceptionHandler(DomainException::class, IllegalArgumentException::class)
    fun invalid(exception: Exception) = error(HttpStatus.BAD_REQUEST, exception.message ?: "Invalid request")

    // a body that isn't JSON, or lacks a required field (e.g. `{}`) - Jackson can't build the request DTO at all
    @ExceptionHandler(HttpMessageNotReadableException::class)
    fun unreadableBody(exception: HttpMessageNotReadableException) =
        error(HttpStatus.BAD_REQUEST, "Malformed request body")

    // e.g. a page or an order id that isn't a number
    @ExceptionHandler(MethodArgumentTypeMismatchException::class)
    fun invalidParameter(exception: MethodArgumentTypeMismatchException) =
        error(HttpStatus.BAD_REQUEST, "Invalid value for ${exception.name}")

    @ExceptionHandler(IllegalStateException::class)
    fun conflict(exception: IllegalStateException) =
        error(HttpStatus.CONFLICT, exception.message ?: "Conflict")

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
    fun internalError(exception: Exception): ResponseEntity<Map<String, Any>> {
        LOG.error(exception.message, exception)
        return error(HttpStatus.INTERNAL_SERVER_ERROR,  "Internal server error")
    }


    private fun error(status: HttpStatus, message: String): ResponseEntity<Map<String, Any>> =
        ResponseEntity.status(status).body(mapOf("status" to status.value(), "error" to message))
}
