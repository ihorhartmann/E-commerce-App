package com.hartmann.application.port.in;

import com.hartmann.application.port.in.dto.PaymentIntentResult;
import com.hartmann.domain.model.Payment;
import com.hartmann.domain.model.PaymentStatus;
import com.hartmann.domain.value.Money;

public interface PaymentService {
    Payment getByOrderId(long orderId);

    PaymentIntentResult submitPayment(long orderId, Money amount);

    Payment updateStatusByExternalId(String externalId, PaymentStatus status);
}
