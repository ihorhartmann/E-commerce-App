package com.hartmann.application.service;

import com.hartmann.application.port.in.PaymentService;
import com.hartmann.application.port.out.repository.PaymentRepository;
import com.hartmann.domain.model.Payment;
import com.hartmann.domain.model.PaymentStatus;
import org.springframework.stereotype.Service;

@Service
public class PaymentServiceImpl implements PaymentService {
    private final PaymentRepository paymentRepository;

    public PaymentServiceImpl(PaymentRepository paymentRepository) {
        this.paymentRepository = paymentRepository;
    }

    @Override
    public Payment getByOrderId(long orderId) {
        return paymentRepository.getByOrderId(orderId);
    }

    @Override
    public Payment create(Payment payment) {
        return paymentRepository.create(payment);
    }

    @Override
    public Payment updateStatus(long paymentId, PaymentStatus status) {
        return paymentRepository.updateStatus(paymentId, status);
    }
}
