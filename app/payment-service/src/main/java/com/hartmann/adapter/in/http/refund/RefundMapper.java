package com.hartmann.adapter.in.http.refund;

import com.hartmann.domain.model.Refund;
import org.springframework.stereotype.Component;

@Component
public class RefundMapper {
    public RefundResponse fromDomainModel(Refund refund) {
        return new RefundResponse(
                refund.id(),
                refund.paymentId(),
                refund.amount().amount(),
                refund.amount().currency().name(),
                refund.status().name(),
                refund.createdAt().toString()
        );
    }
}
