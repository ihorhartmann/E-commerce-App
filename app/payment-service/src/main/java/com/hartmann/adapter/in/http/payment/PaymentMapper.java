package com.hartmann.adapter.in.http.payment;

import com.hartmann.domain.model.Payment;
import org.springframework.stereotype.Component;

@Component
public class PaymentMapper {
    public PaymentResponse fromDomainModel(Payment payment) {
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
}
