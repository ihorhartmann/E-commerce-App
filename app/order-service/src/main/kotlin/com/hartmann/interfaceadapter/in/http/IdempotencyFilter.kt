package com.hartmann.interfaceadapter.`in`.http

import com.hartmann.interfaceadapter.`in`.http.idempotency.HttpIdempotencyService
import com.hartmann.interfaceadapter.`in`.http.idempotency.dto.RequestAttempt
import com.hartmann.interfaceadapter.`in`.http.idempotency.dto.RequestFingerprint
import com.hartmann.interfaceadapter.`in`.http.idempotency.dto.RequestResult
import jakarta.servlet.FilterChain
import jakarta.servlet.ReadListener
import jakarta.servlet.ServletInputStream
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletRequestWrapper
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import org.springframework.web.util.ContentCachingResponseWrapper
import tools.jackson.databind.ObjectMapper
import java.io.BufferedReader
import java.io.ByteArrayInputStream
import java.io.InputStreamReader
import java.time.Duration

private val CLAIM_TTL: Duration = Duration.ofSeconds(60)
private const val IDEMPOTENCY_KEY_HEADER = "Idempotency-Key"
private val MUTATING_METHODS = setOf("POST", "PUT", "PATCH", "DELETE")

/**
 * Protects every mutating request (POST/PUT/PATCH/DELETE) with a client-supplied Idempotency-Key:
 * a retried request with the same key and body gets the original response replayed instead of the
 * handler running again. Unlike the Fiber/Gin middleware in the Go services, Spring MVC's
 * @RestControllerAdvice exception handling runs synchronously inside filterChain.doFilter(), so
 * cachedResponse.status here already reflects the true final status (including handled errors) by
 * the time control returns to this filter - a 4xx response is just as safe to cache and replay as
 * a 2xx one, since both are deterministic given the same input. Only a 5xx is left uncached, since
 * that's usually a transient failure rather than a reproducible function of the request.
 */
@Component
class IdempotencyFilter(
    private val idempotencyService: HttpIdempotencyService,
    private val objectMapper: ObjectMapper,
) : OncePerRequestFilter() {
    companion object {
        private val LOG = LoggerFactory.getLogger(IdempotencyFilter::class.java)
    }

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        if (request.method !in MUTATING_METHODS) {
            filterChain.doFilter(request, response)
            return
        }

        val idempotencyKey = request.getHeader(IDEMPOTENCY_KEY_HEADER)
        if (idempotencyKey.isNullOrBlank()) {
            writeError(response, HttpStatus.BAD_REQUEST, "Missing Idempotency-Key header")
            return
        }

        val cachedRequest = CachedBodyHttpServletRequest(request)
        val requestFingerprint = RequestFingerprint(cachedRequest.method, cachedRequest.requestURI, cachedRequest.bodyBytes())
        val attempt = idempotencyService.claim(idempotencyKey, requestFingerprint, CLAIM_TTL)

        when (attempt.requestResult) {
            RequestResult.CACHED -> {
                writeCached(response, attempt)
                return
            }

            RequestResult.HASH_MISMATCH -> {
                writeError(response, HttpStatus.CONFLICT, "Idempotency-Key was already used with a different request")
                return
            }

            RequestResult.IN_PROGRESS -> {
                writeError(response, HttpStatus.CONFLICT, "A request with this Idempotency-Key is already in progress")
                return
            }

            RequestResult.CLAIMED -> {}
        }

        val cachedResponse = ContentCachingResponseWrapper(response)
        filterChain.doFilter(cachedRequest, cachedResponse)

        // Best-effort: the handler already produced the correct response above, so a failure here
        // must never override it with an error response - it only means this key's replay cache
        // stays stale (reclaimed like any abandoned IN_PROGRESS record).
        if (cachedResponse.status < HttpStatus.INTERNAL_SERVER_ERROR.value()) {
            try {
                idempotencyService.complete(idempotencyKey, cachedResponse.status, String(cachedResponse.contentAsByteArray, Charsets.UTF_8))
            } catch (e: Exception) {
                LOG.error("failed to record idempotent response for key={}", idempotencyKey, e)
            }
        }
        cachedResponse.copyBodyToResponse()
    }

    private fun writeCached(response: HttpServletResponse, attempt: RequestAttempt) {
        val record = requireNotNull(attempt.record)
        response.status = record.httpCode ?: HttpStatus.OK.value()
        response.contentType = "application/json"
        response.writer.write(record.response ?: "")
    }

    private fun writeError(response: HttpServletResponse, status: HttpStatus, message: String) {
        response.status = status.value()
        response.contentType = "application/json"
        response.writer.write(objectMapper.writeValueAsString(mapOf("status" to status.value(), "error" to message)))
    }
}

/**
 * Buffers the whole request body eagerly (in the constructor, straight from the raw request) so
 * it can be read multiple times: once here to compute the idempotency hash, and again downstream
 * by the real handler. Spring's own ContentCachingRequestWrapper looks like it should do this, but
 * it doesn't - it only caches bytes as a side effect of a single, one-time read, so reading it here
 * would leave nothing for the controller to read afterward ("request body is missing").
 */
private class CachedBodyHttpServletRequest(request: HttpServletRequest) : HttpServletRequestWrapper(request) {
    private val body: ByteArray = request.inputStream.readAllBytes()

    override fun getInputStream(): ServletInputStream {
        val delegate = ByteArrayInputStream(body)
        return object : ServletInputStream() {
            override fun read(): Int = delegate.read()
            override fun isFinished(): Boolean = delegate.available() == 0
            override fun isReady(): Boolean = true
            override fun setReadListener(readListener: ReadListener?) {}
        }
    }

    override fun getReader(): BufferedReader =
        BufferedReader(InputStreamReader(inputStream, characterEncoding ?: "UTF-8"))

    fun bodyBytes(): ByteArray = body
}