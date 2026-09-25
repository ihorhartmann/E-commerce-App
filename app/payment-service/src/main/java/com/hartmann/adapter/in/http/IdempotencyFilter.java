package com.hartmann.adapter.in.http;

import com.hartmann.adapter.in.http.idempotency.HttpIdempotencyService;
import com.hartmann.adapter.in.http.idempotency.dto.RequestAttempt;
import com.hartmann.adapter.in.http.idempotency.dto.RequestFingerprint;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;
import tools.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Set;

/**
 * Protects every mutating request (POST/PUT/PATCH/DELETE), except the Stripe webhook - Stripe has
 * its own signature/event-id based idempotency and never sends this header - with a client-supplied
 * Idempotency-Key: a retried request with the same key and body gets the original response replayed
 * instead of the handler running again. Spring MVC's @RestControllerAdvice exception handling runs
 * synchronously inside filterChain.doFilter(), so cachedResponse.getStatus() here already reflects
 * the true final status (including handled errors) by the time control returns to this filter - a
 * 4xx response is just as safe to cache and replay as a 2xx one, since both are deterministic given
 * the same input. Only a 5xx is left uncached, since that's usually a transient failure rather than
 * a reproducible function of the request.
 */
@Component
public class IdempotencyFilter extends OncePerRequestFilter {
    private static final Logger LOG = LoggerFactory.getLogger(IdempotencyFilter.class);
    private static final Duration CLAIM_TTL = Duration.ofSeconds(60);
    public static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";
    private static final Set<String> MUTATING_METHODS = Set.of("POST", "PUT", "PATCH", "DELETE");
    private static final String WEBHOOK_PATH = "/api/v1/webhooks/stripe";

    private final HttpIdempotencyService idempotencyService;
    private final ObjectMapper objectMapper;

    public IdempotencyFilter(HttpIdempotencyService idempotencyService, ObjectMapper objectMapper) {
        this.idempotencyService = idempotencyService;
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        if (!MUTATING_METHODS.contains(request.getMethod()) || WEBHOOK_PATH.equals(request.getRequestURI())) {
            filterChain.doFilter(request, response);
            return;
        }

        var idempotencyKey = request.getHeader(IDEMPOTENCY_KEY_HEADER);
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            writeError(response, HttpStatus.BAD_REQUEST, "Missing Idempotency-Key header");
            return;
        }

        var cachedRequest = new CachedBodyHttpServletRequest(request);
        var requestFingerprint = new RequestFingerprint(cachedRequest.getMethod(), cachedRequest.getRequestURI(), cachedRequest.bodyBytes());
        var attempt = idempotencyService.claim(idempotencyKey, requestFingerprint, CLAIM_TTL);

        switch (attempt.requestResult()) {
            case CACHED -> {
                writeCached(response, attempt);
                return;
            }
            case HASH_MISMATCH -> {
                writeError(response, HttpStatus.CONFLICT, "Idempotency-Key was already used with a different request");
                return;
            }
            case IN_PROGRESS -> {
                writeError(response, HttpStatus.CONFLICT, "A request with this Idempotency-Key is already in progress");
                return;
            }
        }

        var cachedResponse = new ContentCachingResponseWrapper(response);
        filterChain.doFilter(cachedRequest, cachedResponse);

        // Best-effort: the handler already produced the correct response above, so a failure here
        // must never override it with an error response - it only means this key's replay cache
        // stays stale (reclaimed like any abandoned IN_PROGRESS record).
        if (cachedResponse.getStatus() < HttpStatus.INTERNAL_SERVER_ERROR.value()) {
            var body = new String(cachedResponse.getContentAsByteArray(), StandardCharsets.UTF_8);
            try {
                idempotencyService.complete(idempotencyKey, cachedResponse.getStatus(), body);
            } catch (Exception e) {
                LOG.error("failed to record idempotent response for key={}", idempotencyKey, e);
            }
        }
        cachedResponse.copyBodyToResponse();
    }

    private void writeCached(HttpServletResponse response, RequestAttempt attempt) throws IOException {
        var record = attempt.record();
        response.setStatus(record.httpCode() != null ? record.httpCode() : HttpStatus.OK.value());
        response.setContentType("application/json");
        response.getWriter().write(record.response() != null ? record.response() : "");
    }

    private void writeError(HttpServletResponse response, HttpStatus status, String message) throws IOException {
        var problem = ProblemDetail.forStatusAndDetail(status, message);
        response.setStatus(status.value());
        response.setContentType("application/json");
        response.getWriter().write(objectMapper.writeValueAsString(problem));
    }

    /**
     * Buffers the whole request body eagerly (in the constructor, straight from the raw request) so
     * it can be read multiple times: once here to compute the idempotency hash, and again downstream
     * by the real handler. Spring's own ContentCachingRequestWrapper looks like it should do this,
     * but it doesn't - it only caches bytes as a side effect of a single, one-time read, so reading
     * it here would leave nothing for the controller to read afterward ("request body is missing").
     */
    private static class CachedBodyHttpServletRequest extends HttpServletRequestWrapper {
        private final byte[] body;

        CachedBodyHttpServletRequest(HttpServletRequest request) throws IOException {
            super(request);
            this.body = request.getInputStream().readAllBytes();
        }

        byte[] bodyBytes() {
            return body;
        }

        @Override
        public ServletInputStream getInputStream() {
            var stream = new ByteArrayInputStream(body);
            return new ServletInputStream() {
                @Override
                public int read() {
                    return stream.read();
                }

                @Override
                public boolean isFinished() {
                    return stream.available() == 0;
                }

                @Override
                public boolean isReady() {
                    return true;
                }

                @Override
                public void setReadListener(ReadListener readListener) {
                }
            };
        }

        @Override
        public BufferedReader getReader() throws IOException {
            var encoding = getCharacterEncoding() != null ? getCharacterEncoding() : "UTF-8";
            return new BufferedReader(new InputStreamReader(getInputStream(), encoding));
        }
    }
}
