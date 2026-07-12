package com.hartmann.application.port.in;

import com.hartmann.domain.model.Payment;
import com.hartmann.domain.model.PaymentStatus;

public interface PaymentService {
    Payment getByOrderId(long orderId);

    Payment create(Payment payment);

    Payment updateStatus(long paymentId, PaymentStatus status);
}
