package com.hartmann.application.port.out.repository;

import com.hartmann.application.port.out.repository.exception.DuplicateIdempotencyKeyException;
import com.hartmann.domain.model.Refund;
import com.hartmann.domain.model.RefundStatus;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RefundRepository {
    Refund create(Refund refund) throws DuplicateIdempotencyKeyException;

    Optional<Refund> findByIdempotencyKey(UUID idempotencyKey);

    Refund markPending(long refundId, long version, String externalId, RefundStatus status);

    List<Refund> findByPaymentId(long paymentId);

    Refund updateStatus(long refundId, long version, RefundStatus status);

    Refund getByExternalId(String externalId);
}
