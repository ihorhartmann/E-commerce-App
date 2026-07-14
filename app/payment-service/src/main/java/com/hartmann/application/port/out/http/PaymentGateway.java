package com.hartmann.application.port.out.http;

import com.hartmann.application.port.out.http.dto.SubmitPaymentResult;
import com.hartmann.application.port.out.http.dto.SubmitRefundResult;
import com.hartmann.domain.value.Money;

public interface PaymentGateway {
    SubmitPaymentResult submitPayment(long orderId, Money amount);

    SubmitRefundResult submitRefund(String paymentExternalId, Money amount);
}
