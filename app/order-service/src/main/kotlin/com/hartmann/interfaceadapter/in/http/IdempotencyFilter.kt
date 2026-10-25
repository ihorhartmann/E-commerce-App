package com.hartmann.interfaceadapter.`in`.http

import com.hartmann.entities.exception.attributesOf
import com.hartmann.interfaceadapter.`in`.http.exception.IdempotencyKeyReusedException
import com.hartmann.interfaceadapter.`in`.http.exception.MissingIdempotencyKeyException
import com.hartmann.interfaceadapter.`in`.http.exception.RequestInProgressException
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
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import org.springframework.web.servlet.HandlerExceptionResolver
import org.springframework.web.util.ContentCachingResponseWrapper
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
 * handler running again. A success and a permanent failure are cached and replayed alike, since the
 * same request would get the same answer again. Any other failure releases the key at once, so a
 * retry with the same key runs the request again.
 *
 * Unlike the Fiber/Gin middleware in the Go services, Spring MVC's @RestControllerAdvice exception
 * handling runs synchronously inside filterChain.doFilter(), so the response status here is already
 * the final one, errors included - and GlobalExceptionHandler gives every permanent failure a 4xx
 * and every other one a 5xx, so the status alone tells which it was.
 *
 * Its own errors go to GlobalExceptionHandler too, through the [handlerExceptionResolver], so they
 * look like every other error response.
 */
@Component
class IdempotencyFilter(
    private val idempotencyService: HttpIdempotencyService,
    @Qualifier("handlerExceptionResolver") private val handlerExceptionResolver: HandlerExceptionResolver,
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
            writeError(request, response, MissingIdempotencyKeyException())
            return
        }

        val cachedRequest = CachedBodyHttpServletRequest(request)
        val requestFingerprint = RequestFingerprint(cachedRequest.method, cachedRequest.requestURI, cachedRequest.bodyBytes())
        val attempt = try {
            idempotencyService.claim(idempotencyKey, requestFingerprint, CLAIM_TTL)
        } catch (e: Exception) {
            writeError(request, response, e)
            return
        }

        when (attempt.requestResult) {
            RequestResult.CACHED -> {
                replay(response, attempt)
                return
            }

            RequestResult.HASH_MISMATCH -> {
                writeError(request, response, IdempotencyKeyReusedException(idempotencyKey))
                return
            }

            RequestResult.IN_PROGRESS -> {
                writeError(request, response, RequestInProgressException(idempotencyKey))
                return
            }

            RequestResult.CLAIMED -> {}
        }

        val cachedResponse = ContentCachingResponseWrapper(response)
        try {
            filterChain.doFilter(cachedRequest, cachedResponse)
        } catch (e: Exception) {
            // Not a controller's exception - DispatcherServlet hands every one of those to GlobalExceptionHandler,
            // which turns it into the response. Only a failure no handler resolved gets here: GlobalExceptionHandler
            // itself failing, or a filter after this one. There is no response to cache, so the key is released for
            // a retry.
            release(idempotencyKey)
            throw e
        }

        if (cachedResponse.status >= HttpStatus.INTERNAL_SERVER_ERROR.value()) {
            release(idempotencyKey)
        } else {
            // Best-effort: the handler already produced the correct response above, so a failure here
            // must never override it with an error response - it only means this key's replay cache
            // stays stale (reclaimed like any abandoned IN_PROGRESS record).
            try {
                idempotencyService.complete(idempotencyKey, cachedResponse.status, String(cachedResponse.contentAsByteArray, Charsets.UTF_8))
            } catch (e: Exception) {
                logFailure("Failed to record idempotent response", idempotencyKey, e)
            }
        }
        cachedResponse.copyBodyToResponse()
    }

    /** Sends the cached response as it was first sent: an error response keeps its problem+json content type, and an empty one stays empty. */
    private fun replay(response: HttpServletResponse, attempt: RequestAttempt) {
        val record = requireNotNull(attempt.record)
        val httpCode = record.httpCode ?: HttpStatus.OK.value()
        response.status = httpCode
        val body = record.response ?: return

        response.contentType = if (httpCode >= HttpStatus.BAD_REQUEST.value()) {
            MediaType.APPLICATION_PROBLEM_JSON_VALUE
        } else {
            MediaType.APPLICATION_JSON_VALUE
        }
        response.writer.write(body)
    }

    private fun writeError(request: HttpServletRequest, response: HttpServletResponse, exception: Exception) {
        handlerExceptionResolver.resolveException(request, response, null, exception)
    }

    // Best-effort, like recording a response: if it fails, the key is reclaimed once its claim expires.
    private fun release(idempotencyKey: String) {
        try {
            idempotencyService.release(idempotencyKey)
        } catch (e: Exception) {
            logFailure("Failed to release idempotency key", idempotencyKey, e)
        }
    }

    private fun logFailure(message: String, idempotencyKey: String, exception: Exception) {
        val event = LOG.atError().setCause(exception).setMessage(message).addKeyValue("idempotencyKey", idempotencyKey)
        attributesOf(exception).forEach { (key, value) -> event.addKeyValue(key, value) }
        event.log()
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
