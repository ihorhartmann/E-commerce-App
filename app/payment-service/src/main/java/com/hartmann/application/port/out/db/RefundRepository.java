package com.hartmann.application.port.out.db;

import com.hartmann.domain.model.Refund;
import com.hartmann.domain.model.RefundStatus;
import java.util.List;

public interface RefundRepository {
    Refund create(Refund refund);

    List<Refund> findByOrderId(long orderId);

    Refund updateStatus(long refundId, RefundStatus status);
}
