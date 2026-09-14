package com.hartmann.interfaceadapter.`in`.http.idempotency

import com.hartmann.interfaceadapter.`in`.http.idempotency.dto.RequestAttempt
import com.hartmann.interfaceadapter.`in`.http.idempotency.dto.RequestFingerprint
import com.hartmann.interfaceadapter.`in`.http.idempotency.dto.RequestResult
import com.hartmann.usecase.port.out.idempotency.HttpRequestStore
import com.hartmann.usecase.port.out.idempotency.dto.HttpRequestRecord
import org.springframework.stereotype.Service
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant

/**
 * Decides whether a mutating HTTP request should proceed, replay a cached response, or be
 * rejected, for IdempotencyFilter. Composes HttpRequestStore (a persistence port for
 * idempotent_http_requests rows, implemented by interfaceadapter.out.idempotency) but owns all of
 * the actual decision logic itself - the store never branches on what a conflict or a stale
 * record means. HttpIdempotencyService lives here, not in Use cases, because its whole purpose is
 * specific to the HTTP idempotency-key pattern (an Interface adapters-owned concern), not to
 * orchestrating domain behavior.
 */
@Service
class HttpIdempotencyService(
    private val store: HttpRequestStore,
) {
    fun claim(idempotencyKey: String, request: RequestFingerprint, ttl: Duration): RequestAttempt {
        val requestHash = hashRequest(request)
        if (store.insert(idempotencyKey, requestHash)) {
            return RequestAttempt.claimed()
        }

        val record = store.get(idempotencyKey) ?: error("expected an existing record for key=$idempotencyKey")
        return resolveExistingClaim(record, requestHash, ttl)
    }

    private fun resolveExistingClaim(record: HttpRequestRecord, requestHash: String, ttl: Duration): RequestAttempt {
        if (record.requestHash != requestHash) {
            return RequestAttempt(RequestResult.HASH_MISMATCH, record)
        }
        if (record.completedAt != null) {
            return RequestAttempt(RequestResult.CACHED, record)
        }
        if (record.claimedAt.isAfter(Instant.now().minus(ttl))) {
            return RequestAttempt(RequestResult.IN_PROGRESS, record)
        }
        if (!store.reclaim(record.id, ttl)) {
            return RequestAttempt(RequestResult.IN_PROGRESS, record)
        }

        return RequestAttempt.claimed()
    }

    fun complete(idempotencyKey: String, httpCode: Int, response: String) {
        store.complete(idempotencyKey, httpCode, response)
    }

    // internal (not private) so HttpIdempotencyServiceTest can exercise it directly.
    internal fun hashRequest(request: RequestFingerprint): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(request.method.toByteArray())
        digest.update(request.path.toByteArray())
        digest.update(request.body)
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
