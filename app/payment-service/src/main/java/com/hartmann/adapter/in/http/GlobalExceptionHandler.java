package com.hartmann.adapter.in.http;

import com.hartmann.adapter.in.http.exception.AccessForbiddenException;
import com.hartmann.adapter.in.http.exception.HttpErrorCode;
import com.hartmann.adapter.in.http.exception.IdempotencyKeyReusedException;
import com.hartmann.adapter.in.http.exception.InvalidTokenException;
import com.hartmann.adapter.in.http.exception.MissingBearerTokenException;
import com.hartmann.adapter.in.http.exception.RequestInProgressException;
import com.hartmann.domain.exception.ConflictException;
import com.hartmann.domain.exception.ErrorCode;
import com.hartmann.domain.exception.InvalidException;
import com.hartmann.domain.exception.NotFoundException;
import com.hartmann.domain.exception.PaymentServiceException;
import com.hartmann.domain.exception.TransientException;
import com.hartmann.domain.exception.UnavailableException;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.spi.LoggingEventBuilder;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.net.URI;
import java.util.Map;

import static com.hartmann.domain.exception.Attributes.attributesOf;
import static com.hartmann.domain.exception.Attributes.outermostOf;

/**
 * Turns every failed request into an RFC 9457 ProblemDetail response with a {@code code} - the only place that
 * does, like httperror.Write in the Go services. Spring MVC calls it for the exceptions of the controllers; the
 * filters in front of them - IdempotencyFilter, Spring Security's entry point and access-denied handler - hand
 * theirs to it through the HandlerExceptionResolver, so every error response looks the same.
 * <p>
 * PaymentServiceExceptions are mapped by their kind (see {@link PaymentServiceException}), Spring MVC's own
 * exceptions by ResponseEntityExceptionHandler (with a code added in {@link #handleExceptionInternal}), and
 * anything else is a 500 that never shows its message. Each error is logged exactly once, here: a 5xx as an
 * error with its stack trace, anything else as a warning.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {
    private static final Logger LOG = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * Starts ProblemDetail.type: a URN - a name, not a link - built from the code, so it stays the same
     * wherever the error documentation lives, and always matches the code.
     */
    private static final String TYPE_URN_PREFIX = "urn:ecommerce:error:";

    /** What a 503 (and a request still in progress) asks the caller to wait, in seconds. */
    public static final long RETRY_AFTER_SECONDS = 1L;

    public static final String CODE_PROPERTY = "code";
    public static final String RETRYABLE_PROPERTY = "retryable";
    public static final String ERRORS_PROPERTY = "errors";

    @ExceptionHandler(PaymentServiceException.class)
    public ResponseEntity<ProblemDetail> service(PaymentServiceException exception, HttpServletRequest request) {
        return render(exception, exception, request);
    }

    /**
     * Anything that isn't ours is a 500 - unless it only wraps one of ours, e.g. a transaction rolled back
     * because of it, which is reported as the one it wraps.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> unexpected(Exception exception, HttpServletRequest request) {
        var found = outermostOf(exception);
        if (found.isPresent()) {
            return render(exception, found.get(), request);
        }

        var status = HttpStatus.INTERNAL_SERVER_ERROR;
        logFailure(exception, HttpErrorCode.INTERNAL_ERROR, status, request);
        return ResponseEntity.status(status).body(toProblemDetail(HttpErrorCode.INTERNAL_ERROR, status, null, false));
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException exception,
            HttpHeaders headers,
            HttpStatusCode status,
            WebRequest request
    ) {
        var problem = exception.getBody();
        problem.setProperty(
                ERRORS_PROPERTY,
                exception.getBindingResult().getFieldErrors().stream()
                        .map(error -> Map.of("field", error.getField(), "message", String.valueOf(error.getDefaultMessage())))
                        .toList());
        return handleExceptionInternal(exception, problem, headers, status, request);
    }

    // Every Spring MVC exception ResponseEntityExceptionHandler handles ends up here: give its
    // ProblemDetail the same members as ours, and log it. The ProblemDetail is taken from the response super
    // builds, not from body: Spring's own handlers pass a null body and super creates the ProblemDetail from the
    // exception; only an override of ours (handleMethodArgumentNotValid) passes a ready one.
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(
            Exception exception,
            Object body,
            HttpHeaders headers,
            HttpStatusCode statusCode,
            WebRequest request
    ) {
        var code = codeOf(exception, statusCode);
        var servletRequest = request instanceof ServletWebRequest servletWebRequest ? servletWebRequest.getRequest() : null;
        logFailure(exception, code, statusCode, servletRequest);
        var response = super.handleExceptionInternal(exception, body, headers, statusCode, request);
        if (response != null && response.getBody() instanceof ProblemDetail problem) {
            problem.setType(URI.create(TYPE_URN_PREFIX + code.code()));
            problem.setTitle(code.title());
            problem.setProperty(CODE_PROPERTY, code.code());
            problem.setProperty(RETRYABLE_PROPERTY, false);
        }
        return response;
    }

    /** exception is what was thrown; found is ours - the same exception, or the one it wraps. */
    private ResponseEntity<ProblemDetail> render(
            Exception exception,
            PaymentServiceException found,
            HttpServletRequest request
    ) {
        var status = statusOf(found);
        logFailure(exception, found.code(), status, request);

        var response = ResponseEntity.status(status);
        if (found.isRetryable()) {
            response.header(HttpHeaders.RETRY_AFTER, String.valueOf(RETRY_AFTER_SECONDS));
        }
        return response.body(toProblemDetail(found.code(), status, found.externalMessage(), found.isRetryable()));
    }

    /**
     * Decided by the exception's kind, except for the exceptions HTTP gives a status of their own (RFC 9110, the
     * IETF Idempotency-Key draft).
     */
    private static HttpStatus statusOf(PaymentServiceException exception) {
        return switch (exception) {
            case MissingBearerTokenException _, InvalidTokenException _ -> HttpStatus.UNAUTHORIZED;
            case AccessForbiddenException _ -> HttpStatus.FORBIDDEN;
            case IdempotencyKeyReusedException _ -> HttpStatus.UNPROCESSABLE_CONTENT;
            case RequestInProgressException _ -> HttpStatus.CONFLICT;
            case InvalidException _ -> HttpStatus.BAD_REQUEST;
            case NotFoundException _ -> HttpStatus.NOT_FOUND;
            case ConflictException _ -> HttpStatus.CONFLICT;
            case TransientException _, UnavailableException _ -> HttpStatus.SERVICE_UNAVAILABLE;
            default -> HttpStatus.INTERNAL_SERVER_ERROR;
        };
    }

    private static HttpErrorCode codeOf(Exception exception, HttpStatusCode status) {
        return switch (exception) {
            case MethodArgumentNotValidException _ -> HttpErrorCode.VALIDATION_FAILED;
            case HttpMessageNotReadableException _ -> HttpErrorCode.MALFORMED_REQUEST_BODY;
            case MethodArgumentTypeMismatchException _, MissingServletRequestParameterException _ ->
                    HttpErrorCode.INVALID_PARAMETER;
            case MissingRequestHeaderException _ -> HttpErrorCode.MISSING_HEADER;
            case NoResourceFoundException _ -> HttpErrorCode.ROUTE_NOT_FOUND;
            case HttpRequestMethodNotSupportedException _ -> HttpErrorCode.METHOD_NOT_ALLOWED;
            case HttpMediaTypeNotSupportedException _ -> HttpErrorCode.UNSUPPORTED_MEDIA_TYPE;
            default -> status.is5xxServerError() ? HttpErrorCode.INTERNAL_ERROR : HttpErrorCode.INVALID_REQUEST;
        };
    }

    /**
     * The RFC 9457 body of an error response, with the extension members {@code code} and {@code retryable}.
     * {@code instance} is left to Spring MVC, which sets it to the request path when it writes the response.
     */
    private static ProblemDetail toProblemDetail(ErrorCode code, HttpStatusCode status, String detail, boolean retryable) {
        var problem = ProblemDetail.forStatus(status);
        problem.setType(URI.create(TYPE_URN_PREFIX + code.code()));
        problem.setTitle(code.title());
        problem.setDetail(detail);
        problem.setProperty(CODE_PROPERTY, code.code());
        problem.setProperty(RETRYABLE_PROPERTY, retryable);
        return problem;
    }

    // Every value goes in as a separate key-value pair, so a structured log format (Spring Boot's
    // logging.structured.format.*) writes each as its own field: the request, the code and status, the
    // exception's message and the attributes of every PaymentServiceException in its cause chain. A 5xx also
    // gets the stack trace; anything else doesn't need one.
    private static void logFailure(Exception exception, ErrorCode code, HttpStatusCode status, HttpServletRequest request) {
        LoggingEventBuilder event;
        if (status.is5xxServerError()) {
            event = LOG.atError().setCause(exception).setMessage("request failed");
        } else {
            event = LOG.atWarn().setMessage("http request rejected");
        }

        event.addKeyValue("method", request == null ? null : request.getMethod())
                .addKeyValue("path", request == null ? null : request.getRequestURI())
                .addKeyValue("code", code.code())
                .addKeyValue("status", status.value())
                .addKeyValue("exceptionMessage", exception.getMessage());
        attributesOf(exception).forEach(event::addKeyValue);
        event.log();
    }
}
