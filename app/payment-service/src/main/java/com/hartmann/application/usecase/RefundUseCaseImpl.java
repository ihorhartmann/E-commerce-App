package com.hartmann.application.usecase;

import com.hartmann.application.port.in.RefundUseCase;
import com.hartmann.application.port.out.db.RefundRepository;
import com.hartmann.domain.model.Refund;
import com.hartmann.domain.model.RefundStatus;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class RefundUseCaseImpl implements RefundUseCase {
    private final RefundRepository refundRepository;

    public RefundUseCaseImpl(RefundRepository refundRepository) {
        this.refundRepository = refundRepository;
    }

    @Override
    public List<Refund> getByOrderId(long orderId) {
        return refundRepository.findByOrderId(orderId);
    }

    @Override
    public Refund create(Refund refund) {
        return refundRepository.create(refund);
    }

    @Override
    public Refund updateStatus(long refundId, RefundStatus status) {
        return refundRepository.updateStatus(refundId, status);
    }
}
