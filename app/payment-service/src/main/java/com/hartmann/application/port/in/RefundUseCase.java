package com.hartmann.application.port.in;

import com.hartmann.domain.model.Refund;
import com.hartmann.domain.model.RefundStatus;
import java.util.List;

public interface RefundUseCase {
    List<Refund> getByOrderId(long orderId);

    Refund create(Refund refund);

    Refund updateStatus(long refundId, RefundStatus status);
}
