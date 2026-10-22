package com.hartmann.interfaceadapter.`in`.http

import com.hartmann.entities.exception.ConflictException
import com.hartmann.entities.exception.ErrorCode
import com.hartmann.entities.exception.InvalidException
import com.hartmann.entities.exception.NotFoundException
import com.hartmann.entities.exception.OrderServiceException
import com.hartmann.entities.exception.TransientException
import com.hartmann.entities.exception.UnavailableException
import com.hartmann.entities.exception.attributesOf
import com.hartmann.interfaceadapter.`in`.http.exception.AccessForbiddenException
import com.hartmann.interfaceadapter.`in`.http.exception.HttpErrorCode
import com.hartmann.interfaceadapter.`in`.http.exception.IdempotencyKeyReusedException
import com.hartmann.interfaceadapter.`in`.http.exception.InvalidTokenException
import com.hartmann.interfaceadapter.`in`.http.exception.MissingBearerTokenException
import com.hartmann.interfaceadapter.`in`.http.exception.RequestInProgressException
import jakarta.servlet.http.HttpServletRequest
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.HttpStatusCode
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.HttpMediaTypeNotSupportedException
import org.springframework.web.HttpRequestMethodNotSupportedException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.MissingRequestHeaderException
import org.springframework.web.bind.MissingServletRequestParameterException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.context.request.ServletWebRequest
import org.springframework.web.context.request.WebRequest
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler
import org.springframework.web.servlet.resource.NoResourceFoundException
import java.net.URI

/**
 * Turns every failed request into an RFC 9457 ProblemDetail response with a `code` - the only place that does,
 * like httperror.Write in the Go services. Spring MVC calls it for the exceptions of the controllers; the
 * filters in front of them - IdempotencyFilter, Spring Security's entry point and access-denied handler - hand
 * theirs to it through the HandlerExceptionResolver, so every error response looks the same.
 *
 * OrderServiceExceptions are mapped by their kind (see [OrderServiceException]), Spring MVC's own exceptions by
 * ResponseEntityExceptionHandler (with a code added in [handleExceptionInternal]), and anything else is a 500
 * that never shows its message. Each error is logged exactly once, here: a 5xx as an error with its stack
 * trace, anything else as a warning.
 */
@RestControllerAdvice
class GlobalExceptionHandler : ResponseEntityExceptionHandler() {
    companion object {
        private val LOG: Logger = LoggerFactory.getLogger(GlobalExceptionHandler::class.java)

        /**
         * Starts ProblemDetail.type: a URN - a name, not a link - built from the code, so it stays the same
         * wherever the error documentation lives, and always matches the code.
         */
        private const val TYPE_URN_PREFIX = "urn:ecommerce:error:"

        /** What a 503 (and a request still in progress) asks the caller to wait, in seconds. */
        const val RETRY_AFTER_SECONDS = 1L

        const val CODE_PROPERTY = "code"
        const val RETRYABLE_PROPERTY = "retryable"
        const val ERRORS_PROPERTY = "errors"
    }

    @ExceptionHandler(OrderServiceException::class)
    fun service(exception: OrderServiceException, request: HttpServletRequest): ResponseEntity<ProblemDetail> =
        render(exception, exception, request)

    /**
     * Anything that isn't ours is a 500 - unless it only wraps one of ours, e.g. a transaction rolled back
     * because of it, which is reported as the one it wraps.
     */
    @ExceptionHandler(Exception::class)
    fun unexpected(exception: Exception, request: HttpServletRequest): ResponseEntity<ProblemDetail> {
        val found = generateSequence(exception.cause) { it.cause }.filterIsInstance<OrderServiceException>().firstOrNull()
        if (found != null) {
            return render(exception, found, request)
        }

        val status = HttpStatus.INTERNAL_SERVER_ERROR
        logFailure(exception, HttpErrorCode.INTERNAL_ERROR, status, request)
        return ResponseEntity.status(status).body(toProblemDetail(HttpErrorCode.INTERNAL_ERROR, status, null, false))
    }

    override fun handleMethodArgumentNotValid(
        exception: MethodArgumentNotValidException,
        headers: HttpHeaders,
        status: HttpStatusCode,
        request: WebRequest,
    ): ResponseEntity<Any>? {
        val problem = exception.body
        problem.setProperty(
            ERRORS_PROPERTY,
            exception.bindingResult.fieldErrors.map { mapOf("field" to it.field, "message" to it.defaultMessage) },
        )
        return handleExceptionInternal(exception, problem, headers, status, request)
    }

    // Every Spring MVC exception ResponseEntityExceptionHandler handles ends up here: give its
    // ProblemDetail the same members as ours, and log it.
    override fun handleExceptionInternal(
        exception: Exception,
        body: Any?,
        headers: HttpHeaders,
        statusCode: HttpStatusCode,
        request: WebRequest,
    ): ResponseEntity<Any>? {
        val code = codeOf(exception, statusCode)
        logFailure(exception, code, statusCode, (request as? ServletWebRequest)?.request)
        if (body is ProblemDetail) {
            body.type = URI.create(TYPE_URN_PREFIX + code.code)
            body.title = code.title
            body.setProperty(CODE_PROPERTY, code.code)
            body.setProperty(RETRYABLE_PROPERTY, false)
        }
        return super.handleExceptionInternal(exception, body, headers, statusCode, request)
    }

    /** [exception] is what was thrown; [found] is ours - the same exception, or the one it wraps. */
    private fun render(
        exception: Exception,
        found: OrderServiceException,
        request: HttpServletRequest,
    ): ResponseEntity<ProblemDetail> {
        val status = statusOf(found)
        logFailure(exception, found.code, status, request)

        val response = ResponseEntity.status(status)
        if (found.retryable) {
            response.header(HttpHeaders.RETRY_AFTER, RETRY_AFTER_SECONDS.toString())
        }
        return response.body(toProblemDetail(found.code, status, found.externalMessage, found.retryable))
    }

    /**
     * Decided by the exception's kind, except for the exceptions HTTP gives a status of their own (RFC 9110, the
     * IETF Idempotency-Key draft).
     */
    private fun statusOf(exception: OrderServiceException): HttpStatus = when (exception) {
        is MissingBearerTokenException, is InvalidTokenException -> HttpStatus.UNAUTHORIZED
        is AccessForbiddenException -> HttpStatus.FORBIDDEN
        is IdempotencyKeyReusedException -> HttpStatus.UNPROCESSABLE_CONTENT
        is RequestInProgressException -> HttpStatus.CONFLICT
        is InvalidException -> HttpStatus.BAD_REQUEST
        is NotFoundException -> HttpStatus.NOT_FOUND
        is ConflictException -> HttpStatus.CONFLICT
        is TransientException, is UnavailableException -> HttpStatus.SERVICE_UNAVAILABLE
        else -> HttpStatus.INTERNAL_SERVER_ERROR
    }

    private fun codeOf(exception: Exception, status: HttpStatusCode): HttpErrorCode = when (exception) {
        is MethodArgumentNotValidException -> HttpErrorCode.VALIDATION_FAILED
        is HttpMessageNotReadableException -> HttpErrorCode.MALFORMED_REQUEST_BODY
        is MethodArgumentTypeMismatchException, is MissingServletRequestParameterException -> HttpErrorCode.INVALID_PARAMETER
        is MissingRequestHeaderException -> HttpErrorCode.MISSING_HEADER
        is NoResourceFoundException -> HttpErrorCode.ROUTE_NOT_FOUND
        is HttpRequestMethodNotSupportedException -> HttpErrorCode.METHOD_NOT_ALLOWED
        is HttpMediaTypeNotSupportedException -> HttpErrorCode.UNSUPPORTED_MEDIA_TYPE
        else -> if (status.is5xxServerError) HttpErrorCode.INTERNAL_ERROR else HttpErrorCode.INVALID_REQUEST
    }

    /**
     * The RFC 9457 body of an error response, with the extension members `code` and `retryable`. `instance` is
     * left to Spring MVC, which sets it to the request path when it writes the response.
     */
    private fun toProblemDetail(code: ErrorCode, status: HttpStatusCode, detail: String?, retryable: Boolean) =
        ProblemDetail.forStatus(status).apply {
            type = URI.create(TYPE_URN_PREFIX + code.code)
            title = code.title
            this.detail = detail
            setProperty(CODE_PROPERTY, code.code)
            setProperty(RETRYABLE_PROPERTY, retryable)
        }

    // Every value goes in as a separate key-value pair, so a structured log format (Spring Boot's
    // logging.structured.format.*) writes each as its own field: the request, the code and status, the
    // exception's message and the attributes of every OrderServiceException in its cause chain. A 5xx also
    // gets the stack trace; anything else doesn't need one.
    private fun logFailure(exception: Exception, code: ErrorCode, status: HttpStatusCode, request: HttpServletRequest?) {
        val event = if (status.is5xxServerError) {
            LOG.atError().setCause(exception).setMessage("Request failed")
        } else {
            LOG.atWarn().setMessage("Request rejected")
        }

        event.addKeyValue("method", request?.method)
            .addKeyValue("path", request?.requestURI)
            .addKeyValue("code", code.code)
            .addKeyValue("status", status.value())
            .addKeyValue("exceptionMessage", exception.message)
        attributesOf(exception).forEach { (key, value) -> event.addKeyValue(key, value) }
        event.log()
    }
}
