package com.hartmann.adapter.in.http.idempotency;

import com.hartmann.adapter.in.http.idempotency.dto.RequestAttempt;
import com.hartmann.adapter.in.http.idempotency.dto.RequestFingerprint;
import com.hartmann.adapter.in.http.idempotency.dto.RequestResult;
import com.hartmann.application.port.out.idempotency.HttpRequestStore;
import com.hartmann.application.port.out.idempotency.dto.HttpRequestRecord;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;

@Service
public class HttpIdempotencyService {
    private final HttpRequestStore store;

    public HttpIdempotencyService(HttpRequestStore store) {
        this.store = store;
    }

    public RequestAttempt claim(String idempotencyKey, RequestFingerprint request, Duration ttl) {
        var requestHash = hashRequest(request);
        if (store.insert(idempotencyKey, requestHash)) {
            return RequestAttempt.claimed();
        }

        var record = store.find(idempotencyKey).orElseThrow();
        return resolveExistingClaim(record, requestHash, ttl);
    }

    private RequestAttempt resolveExistingClaim(HttpRequestRecord record, String requestHash, Duration ttl) {
        if (!record.requestHash().equals(requestHash)) {
            return new RequestAttempt(RequestResult.HASH_MISMATCH, record);
        }
        if (record.completedAt() != null) {
            return new RequestAttempt(RequestResult.CACHED, record);
        }
        if (record.claimedAt().isAfter(Instant.now().minus(ttl))) {
            return new RequestAttempt(RequestResult.IN_PROGRESS, record);
        }
        if (!store.reclaim(record.id(), ttl)) {
            return new RequestAttempt(RequestResult.IN_PROGRESS, record);
        }

        return RequestAttempt.claimed();
    }

    public void complete(String idempotencyKey, int httpCode, String response) {
        store.complete(idempotencyKey, httpCode, response);
    }

    /**
     * Gives up idempotencyKey's claim after a failure repeating the same request may fix, so a retry with the
     * same key runs it again at once.
     */
    public void release(String idempotencyKey) {
        store.release(idempotencyKey);
    }

    String hashRequest(RequestFingerprint request) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            digest.update(request.method().getBytes(StandardCharsets.UTF_8));
            digest.update(request.path().getBytes(StandardCharsets.UTF_8));
            digest.update(request.body());
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }
}
