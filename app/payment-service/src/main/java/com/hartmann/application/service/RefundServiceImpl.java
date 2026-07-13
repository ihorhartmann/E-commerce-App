package com.hartmann.application.service;

import com.hartmann.application.port.in.RefundService;
import com.hartmann.application.port.out.repository.RefundRepository;
import com.hartmann.domain.model.Refund;
import com.hartmann.domain.model.RefundStatus;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class RefundServiceImpl implements RefundService {
    private final RefundRepository refundRepository;

    public RefundServiceImpl(RefundRepository refundRepository) {
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
