package com.hartmann.adapter.in.http.refund.mapper;

import com.hartmann.adapter.in.http.refund.dto.RefundResponse;
import com.hartmann.adapter.in.http.refund.dto.SubmitRefundRequest;
import com.hartmann.domain.model.Refund;
import com.hartmann.domain.value.Currency;
import com.hartmann.domain.value.Money;
import org.springframework.stereotype.Component;

@Component
public class RefundMapper {
    public RefundResponse toRefundResponse(Refund refund) {
        return new RefundResponse(
                refund.id(),
                refund.paymentId(),
                refund.amount().amount(),
                refund.amount().currency().name(),
                refund.status().name(),
                refund.createdAt().toString()
        );
    }

    public Money fromSubmitRefundRequest(SubmitRefundRequest request) {
        return new Money(request.amount(), Currency.fromCode(request.currency()));
    }
}
