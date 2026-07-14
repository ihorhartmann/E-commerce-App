package com.hartmann.adapter.in.http.refund.mapper;

import com.hartmann.adapter.in.http.refund.dto.RefundResponse;
import com.hartmann.domain.model.Refund;
import org.springframework.stereotype.Component;

@Component
public class RefundMapper {
    public RefundResponse fromDomain(Refund refund) {
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
