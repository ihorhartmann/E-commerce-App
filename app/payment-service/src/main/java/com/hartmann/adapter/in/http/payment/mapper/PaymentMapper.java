package com.hartmann.adapter.in.http.payment.mapper;

import com.hartmann.adapter.in.http.payment.dto.PaymentResponse;
import com.hartmann.adapter.in.http.payment.dto.SubmitPaymentRequest;
import com.hartmann.adapter.in.http.payment.dto.SubmitPaymentResponse;
import com.hartmann.application.port.in.dto.PaymentIntentResult;
import com.hartmann.domain.model.Payment;
import com.hartmann.domain.value.Currency;
import com.hartmann.domain.value.Money;
import org.springframework.stereotype.Component;

@Component
public class PaymentMapper {
    public PaymentResponse toPaymentResponse(Payment payment) {
        return new PaymentResponse(
                payment.id(),
                payment.orderId(),
                payment.amount().amount(),
                payment.amount().currency().name(),
                payment.status().name(),
                payment.createdAt().toString(),
                payment.updatedAt().toString()
        );
    }

    public SubmitPaymentResponse toSubmitPaymentResponse(PaymentIntentResult result) {
        var payment = result.payment();
        return new SubmitPaymentResponse(
                payment.id(),
                payment.orderId(),
                payment.externalId(),
                result.clientSecret(),
                payment.status().name()
        );
    }

    public Money fromSubmitPaymentRequest(SubmitPaymentRequest request) {
        return new Money(request.amount(), Currency.fromCode(request.currency()));
    }
}
