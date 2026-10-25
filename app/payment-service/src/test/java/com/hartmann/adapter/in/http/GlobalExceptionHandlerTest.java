package com.hartmann.adapter.in.http;

import com.hartmann.adapter.in.http.exception.HttpErrorCode;
import com.hartmann.adapter.in.http.exception.IdempotencyKeyReusedException;
import com.hartmann.adapter.in.http.exception.RequestInProgressException;
import com.hartmann.domain.exception.ConflictException;
import com.hartmann.domain.exception.ErrorCode;
import com.hartmann.domain.exception.UnavailableException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.context.request.ServletWebRequest;

import java.net.URI;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@TestInstance(TestInstance.Lifecycle.PER_METHOD)
class GlobalExceptionHandlerTest {
    private enum TestErrorCode implements ErrorCode {
        TEST_CONFLICT("Test conflict"),
        TEST_UNAVAILABLE("Test unavailable");

        private final String title;

        TestErrorCode(String title) {
            this.title = title;
        }

        @Override
        public String title() {
            return title;
        }
    }

    private static class TestConflictException extends ConflictException {
        TestConflictException(long paymentId, String externalMessage) {
            super(TestErrorCode.TEST_CONFLICT, "Refund exceeds the refundable balance", Map.of("paymentId", paymentId),
                    externalMessage, null);
        }
    }

    private static class TestUnavailableException extends UnavailableException {
        TestUnavailableException() {
            super(TestErrorCode.TEST_UNAVAILABLE, "Stripe answered 503 for order 7");
        }
    }

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();
    private final MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/payments");

    @Test
    void service_rendersAConflictAs409WithItsExternalMessageAndNeverItsInternalOne() {
        var detail = "The refund amount exceeds the refundable balance.";

        var response = handler.service(new TestConflictException(5L, detail), request);

        var problem = response.getBody();
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(problem.getStatus()).isEqualTo(HttpStatus.CONFLICT.value());
        assertThat(problem.getType()).isEqualTo(URI.create("urn:ecommerce:error:PAYMENT.TEST_CONFLICT"));
        assertThat(problem.getTitle()).isEqualTo(TestErrorCode.TEST_CONFLICT.title());
        assertThat(problem.getDetail()).isEqualTo(detail);
        assertThat(problem.getProperties()).containsEntry(GlobalExceptionHandler.CODE_PROPERTY, "PAYMENT.TEST_CONFLICT");
        assertThat(problem.getProperties()).containsEntry(GlobalExceptionHandler.RETRYABLE_PROPERTY, false);
        assertThat(response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isNull();
    }

    @Test
    void service_rendersAnUnavailableDependencyAsARetryable503WithRetryAfter() {
        var response = handler.service(new TestUnavailableException(), request);

        var problem = response.getBody();
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(problem.getProperties()).containsEntry(GlobalExceptionHandler.CODE_PROPERTY, TestErrorCode.TEST_UNAVAILABLE.code());
        assertThat(problem.getProperties()).containsEntry(GlobalExceptionHandler.RETRYABLE_PROPERTY, true);
        assertThat(problem.getDetail()).isNull();
        assertThat(response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER))
                .isEqualTo(String.valueOf(GlobalExceptionHandler.RETRY_AFTER_SECONDS));
    }

    @Test
    void service_rendersAReusedIdempotencyKeyAs422() {
        var response = handler.service(new IdempotencyKeyReusedException("key-1"), request);

        var problem = response.getBody();
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(problem.getProperties()).containsEntry(GlobalExceptionHandler.CODE_PROPERTY, HttpErrorCode.IDEMPOTENCY_KEY_REUSED.code());
        assertThat(problem.getProperties()).containsEntry(GlobalExceptionHandler.RETRYABLE_PROPERTY, false);
    }

    @Test
    void service_rendersARequestInProgressAsARetryable409WithRetryAfter() {
        var response = handler.service(new RequestInProgressException("key-1"), request);

        var problem = response.getBody();
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(problem.getProperties()).containsEntry(GlobalExceptionHandler.CODE_PROPERTY, HttpErrorCode.REQUEST_IN_PROGRESS.code());
        assertThat(problem.getProperties()).containsEntry(GlobalExceptionHandler.RETRYABLE_PROPERTY, true);
        assertThat(response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER))
                .isEqualTo(String.valueOf(GlobalExceptionHandler.RETRY_AFTER_SECONDS));
    }

    @Test
    void unexpected_rendersAnyOtherExceptionAs500WithoutItsMessage() {
        var response = handler.unexpected(new IllegalStateException("pq: password authentication failed"), request);

        var problem = response.getBody();
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(problem.getTitle()).isEqualTo(HttpErrorCode.INTERNAL_ERROR.title());
        assertThat(problem.getProperties()).containsEntry(GlobalExceptionHandler.CODE_PROPERTY, HttpErrorCode.INTERNAL_ERROR.code());
        assertThat(problem.getProperties()).containsEntry(GlobalExceptionHandler.RETRYABLE_PROPERTY, false);
        assertThat(problem.getDetail()).isNull();
    }

    @Test
    void handleExceptionInternal_givesTheCodeToAProblemDetailSpringBuildsItself() throws Exception {
        var response = handler.handleException(new HttpRequestMethodNotSupportedException("DELETE"), new ServletWebRequest(request));

        var problem = (ProblemDetail) response.getBody();
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
        assertThat(problem.getType()).isEqualTo(URI.create("urn:ecommerce:error:" + HttpErrorCode.METHOD_NOT_ALLOWED.code()));
        assertThat(problem.getTitle()).isEqualTo(HttpErrorCode.METHOD_NOT_ALLOWED.title());
        assertThat(problem.getProperties()).containsEntry(GlobalExceptionHandler.CODE_PROPERTY, HttpErrorCode.METHOD_NOT_ALLOWED.code());
        assertThat(problem.getProperties()).containsEntry(GlobalExceptionHandler.RETRYABLE_PROPERTY, false);
    }

    @Test
    void unexpected_rendersAnExceptionWrappingOneOfOursAsTheOneItWraps() {
        var detail = "The refund amount exceeds the refundable balance.";

        var response = handler.unexpected(
                new RuntimeException("transaction rolled back", new TestConflictException(5L, detail)), request);

        var problem = response.getBody();
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(problem.getProperties()).containsEntry(GlobalExceptionHandler.CODE_PROPERTY, TestErrorCode.TEST_CONFLICT.code());
        assertThat(problem.getDetail()).isEqualTo(detail);
    }
}
