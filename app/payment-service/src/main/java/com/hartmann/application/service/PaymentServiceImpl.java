package com.hartmann.application.service;

import com.hartmann.application.port.in.PaymentService;
import com.hartmann.application.port.out.http.PaymentGateway;
import com.hartmann.application.port.out.repository.PaymentRepository;
import com.hartmann.domain.model.Payment;
import com.hartmann.domain.model.PaymentStatus;
import com.hartmann.domain.value.Money;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

@Service
public class PaymentServiceImpl implements PaymentService {
    private final PaymentRepository paymentRepository;
    private final PaymentGateway paymentGateway;

    public PaymentServiceImpl(PaymentRepository paymentRepository, PaymentGateway paymentGateway) {
        this.paymentRepository = paymentRepository;
        this.paymentGateway = paymentGateway;
    }

    @Override
    public Payment getByOrderId(long orderId) {
        return paymentRepository.getByOrderId(orderId);
    }

    @Override
    public Payment submitPayment(long orderId, Money amount) {
        var result = paymentGateway.submitPayment(orderId, amount);
        var now = Instant.now();

        return paymentRepository.create(
                new Payment(0L, orderId, result.externalId(), amount, PaymentStatus.PENDING, List.of(), now, now)
        );
    }

    @Override
    public Payment updateStatusByExternalId(String externalId, PaymentStatus status) {
        var payment = paymentRepository.getByExternalId(externalId);
        return paymentRepository.updateStatus(payment.id(), status);
    }
}
