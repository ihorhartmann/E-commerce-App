package com.hartmann.application.port.in;

import com.hartmann.domain.model.Refund;
import com.hartmann.domain.model.RefundStatus;
import com.hartmann.domain.value.Money;
import java.util.List;

public interface RefundService {
    List<Refund> getByOrderId(long orderId);

    Refund submitRefund(long paymentId, Money amount);

    Refund updateStatusByExternalId(String externalId, RefundStatus status);
}
