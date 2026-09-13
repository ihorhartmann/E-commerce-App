package com.hartmann.adapter.in.http.idempotency;

import com.hartmann.adapter.in.http.idempotency.dto.RequestFingerprint;
import com.hartmann.adapter.in.http.idempotency.dto.RequestResult;
import com.hartmann.application.port.out.idempotency.HttpRequestStore;
import com.hartmann.application.port.out.idempotency.dto.HttpRequestRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@TestInstance(TestInstance.Lifecycle.PER_METHOD)
class HttpIdempotencyServiceTest {

    @Mock
    private HttpRequestStore store;

    private HttpIdempotencyService service;

    private static final Duration TTL = Duration.ofSeconds(60);

    @BeforeEach
    void setUp() {
        service = new HttpIdempotencyService(store);
    }

    private RequestFingerprint testRequest() {
        return new RequestFingerprint("POST", "/api/v1/refunds", "{\"amount\":\"40.00\"}".getBytes(StandardCharsets.UTF_8));
    }

    private HttpRequestRecord testRecord(String requestHash, Instant claimedAt, Instant completedAt) {
        return new HttpRequestRecord("key-1", requestHash, completedAt == null ? null : 201, completedAt == null ? null : "{}",
                Instant.now(), claimedAt, completedAt);
    }

    @Test
    void hashRequest_computesTheSameHashForTheSameMethodPathAndBody() {
        var first = service.hashRequest(testRequest());
        var second = service.hashRequest(testRequest());

        assertThat(first).isEqualTo(second);
    }

    @Test
    void hashRequest_computesADifferentHashWhenTheBodyDiffers() {
        var request = testRequest();
        var other = new RequestFingerprint(request.method(), request.path(), "{\"amount\":\"99.00\"}".getBytes(StandardCharsets.UTF_8));

        var got = service.hashRequest(request);
        var want = service.hashRequest(other);

        assertThat(got).isNotEqualTo(want);
    }

    @Test
    void claim_insertsAndHashesTheRequestWhenTheKeyIsUnclaimed() {
        var idempotencyKey = "key-1";
        var request = testRequest();
        when(store.insert(eq(idempotencyKey), any())).thenReturn(true);

        var result = service.claim(idempotencyKey, request, TTL);

        assertThat(result.requestResult()).isEqualTo(RequestResult.CLAIMED);
        assertThat(result.record()).isNull();
        var hashCaptor = ArgumentCaptor.forClass(String.class);
        verify(store, times(1)).insert(eq(idempotencyKey), hashCaptor.capture());
        assertThat(hashCaptor.getValue()).isEqualTo(service.hashRequest(request));
        verify(store, never()).get(any());
        verify(store, never()).reclaim(any(), any());
    }

    @Test
    void claim_returnsHashMismatchWhenTheExistingRecordWasStoredForADifferentRequest() {
        var idempotencyKey = "key-1";
        var request = testRequest();
        var record = testRecord("a-different-hash", Instant.now(), null);
        when(store.insert(eq(idempotencyKey), any())).thenReturn(false);
        when(store.get(idempotencyKey)).thenReturn(Optional.of(record));

        var result = service.claim(idempotencyKey, request, TTL);

        assertThat(result.requestResult()).isEqualTo(RequestResult.HASH_MISMATCH);
        assertThat(result.record()).isEqualTo(record);
        verify(store, times(1)).insert(eq(idempotencyKey), any());
        verify(store, times(1)).get(idempotencyKey);
        verify(store, never()).reclaim(any(), any());
    }

    @Test
    void claim_returnsCachedWhenTheExistingRecordAlreadyCompleted() {
        var idempotencyKey = "key-1";
        var request = testRequest();
        var record = testRecord(service.hashRequest(request), Instant.now(), Instant.now());
        when(store.insert(eq(idempotencyKey), any())).thenReturn(false);
        when(store.get(idempotencyKey)).thenReturn(Optional.of(record));

        var result = service.claim(idempotencyKey, request, TTL);

        assertThat(result.requestResult()).isEqualTo(RequestResult.CACHED);
        assertThat(result.record()).isEqualTo(record);
        verify(store, times(1)).insert(eq(idempotencyKey), any());
        verify(store, times(1)).get(idempotencyKey);
        verify(store, never()).reclaim(any(), any());
    }

    @Test
    void claim_returnsInProgressWhenTheExistingClaimIsStillFresh() {
        var idempotencyKey = "key-1";
        var request = testRequest();
        var record = testRecord(service.hashRequest(request), Instant.now(), null);
        when(store.insert(eq(idempotencyKey), any())).thenReturn(false);
        when(store.get(idempotencyKey)).thenReturn(Optional.of(record));

        var result = service.claim(idempotencyKey, request, TTL);

        assertThat(result.requestResult()).isEqualTo(RequestResult.IN_PROGRESS);
        assertThat(result.record()).isEqualTo(record);
        verify(store, times(1)).insert(eq(idempotencyKey), any());
        verify(store, times(1)).get(idempotencyKey);
        verify(store, never()).reclaim(any(), any());
    }

    @Test
    void claim_reclaimsAndReturnsClaimedWhenTheExistingClaimIsStale() {
        var idempotencyKey = "key-1";
        var request = testRequest();
        var record = testRecord(service.hashRequest(request), Instant.now().minus(TTL).minusSeconds(1), null);
        when(store.insert(eq(idempotencyKey), any())).thenReturn(false);
        when(store.get(idempotencyKey)).thenReturn(Optional.of(record));
        when(store.reclaim(idempotencyKey, TTL)).thenReturn(true);

        var result = service.claim(idempotencyKey, request, TTL);

        assertThat(result.requestResult()).isEqualTo(RequestResult.CLAIMED);
        assertThat(result.record()).isNull();
        verify(store, times(1)).insert(eq(idempotencyKey), any());
        verify(store, times(1)).get(idempotencyKey);
        verify(store, times(1)).reclaim(idempotencyKey, TTL);
    }

    @Test
    void claim_returnsInProgressWhenTheStaleClaimWasAlreadyReclaimedBySomeoneElse() {
        var idempotencyKey = "key-1";
        var request = testRequest();
        var record = testRecord(service.hashRequest(request), Instant.now().minus(TTL).minusSeconds(1), null);
        when(store.insert(eq(idempotencyKey), any())).thenReturn(false);
        when(store.get(idempotencyKey)).thenReturn(Optional.of(record));
        when(store.reclaim(idempotencyKey, TTL)).thenReturn(false);

        var result = service.claim(idempotencyKey, request, TTL);

        assertThat(result.requestResult()).isEqualTo(RequestResult.IN_PROGRESS);
        assertThat(result.record()).isEqualTo(record);
        verify(store, times(1)).insert(eq(idempotencyKey), any());
        verify(store, times(1)).get(idempotencyKey);
        verify(store, times(1)).reclaim(idempotencyKey, TTL);
    }

    @Test
    void complete_delegatesToTheStore() {
        var idempotencyKey = "key-1";
        var httpCode = 201;
        var response = "{\"status\":\"ok\"}";

        service.complete(idempotencyKey, httpCode, response);

        verify(store, times(1)).complete(idempotencyKey, httpCode, response);
    }
}
