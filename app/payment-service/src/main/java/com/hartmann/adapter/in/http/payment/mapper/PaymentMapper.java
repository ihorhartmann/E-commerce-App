package com.hartmann.adapter.in.http.payment.mapper;

import com.hartmann.adapter.in.http.payment.dto.PaymentResponse;
import com.hartmann.adapter.in.http.payment.dto.SubmitPaymentResponse;
import com.hartmann.domain.model.Payment;
import org.springframework.stereotype.Component;

@Component
public class PaymentMapper {
    public PaymentResponse fromDomain(Payment payment) {
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

    public SubmitPaymentResponse toSubmitPaymentResponse(Payment payment) {
        return new SubmitPaymentResponse(
                payment.id(),
                payment.orderId(),
                payment.externalId(),
                payment.status().name()
        );
    }
}
