package com.hartmann.application.port.in;

import com.hartmann.domain.model.Payment;
import com.hartmann.domain.model.PaymentStatus;
import com.hartmann.domain.value.Money;

public interface PaymentService {
    Payment getByOrderId(long orderId);

    Payment submitPayment(long orderId, Money amount);

    Payment updateStatusByExternalId(String externalId, PaymentStatus status);
}
