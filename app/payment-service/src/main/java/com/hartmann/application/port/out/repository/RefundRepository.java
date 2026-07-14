package com.hartmann.application.port.out.repository;

import com.hartmann.domain.model.Refund;
import com.hartmann.domain.model.RefundStatus;
import java.util.List;

public interface RefundRepository {
    Refund create(Refund refund);

    List<Refund> findByPaymentId(long paymentId);

    Refund updateStatus(long refundId, RefundStatus status);

    Refund getByExternalId(String externalId);
}
