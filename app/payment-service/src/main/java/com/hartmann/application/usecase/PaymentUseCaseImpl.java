package com.hartmann.application.usecase;

import com.hartmann.application.port.in.PaymentUseCase;
import com.hartmann.application.port.out.db.PaymentRepository;
import com.hartmann.domain.model.Payment;
import com.hartmann.domain.model.PaymentStatus;
import org.springframework.stereotype.Service;

@Service
public class PaymentUseCaseImpl implements PaymentUseCase {
    private final PaymentRepository paymentRepository;

    public PaymentUseCaseImpl(PaymentRepository paymentRepository) {
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
