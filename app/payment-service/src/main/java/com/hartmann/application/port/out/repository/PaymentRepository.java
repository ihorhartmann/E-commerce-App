package com.hartmann.application.port.out.repository;

import com.hartmann.domain.model.Payment;
import com.hartmann.domain.model.PaymentStatus;

public interface PaymentRepository {
    Payment create(Payment payment);

    Payment getById(long paymentId);

    Payment getByOrderId(long orderId);

    Payment updateStatus(long paymentId, PaymentStatus status);

    Payment getByExternalId(String externalId);

    Payment getByIdWithRefunds(long paymentId);
}
