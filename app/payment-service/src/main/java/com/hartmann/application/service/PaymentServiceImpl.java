package com.hartmann.application.service;

import com.hartmann.application.port.in.PaymentService;
import com.hartmann.application.port.in.dto.PaymentIntentResult;
import com.hartmann.application.port.out.http.PaymentGateway;
import com.hartmann.application.port.out.kafka.PaymentKafkaProducer;
import com.hartmann.application.port.out.repository.PaymentRepository;
import com.hartmann.domain.model.Payment;
import com.hartmann.domain.model.PaymentStatus;
import com.hartmann.domain.value.Money;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PaymentServiceImpl implements PaymentService {
    private final PaymentRepository paymentRepository;
    private final PaymentGateway paymentGateway;
    private final PaymentKafkaProducer paymentKafkaProducer;

    public PaymentServiceImpl(
            PaymentRepository paymentRepository,
            PaymentGateway paymentGateway,
            PaymentKafkaProducer paymentKafkaProducer
    ) {
        this.paymentRepository = paymentRepository;
        this.paymentGateway = paymentGateway;
        this.paymentKafkaProducer = paymentKafkaProducer;
    }

    @Override
    public Payment getByOrderId(long orderId) {
        return paymentRepository.getByOrderId(orderId);
    }

    @Override
    public PaymentIntentResult submitPayment(long orderId, Money amount) {
        var result = paymentGateway.submitPayment(orderId, amount);

        //TODO create before submit, and then update with externalId
        var payment = paymentRepository.create(
                Payment.createNew(orderId, result.externalId(), amount)
        );

        return new PaymentIntentResult(payment, result.clientSecret());
    }

    @Override
    @Transactional
    public Payment updateStatusByExternalId(String externalId, PaymentStatus status) {
        var payment = paymentRepository.getByExternalId(externalId);
        var updatedPayment = paymentRepository.updateStatus(payment.id(), status);

        if (status == PaymentStatus.COMPLETED) {
            paymentKafkaProducer.publishPaymentCompleted(updatedPayment.orderId());
        } else if (status == PaymentStatus.FAILED) {
            paymentKafkaProducer.publishPaymentFailed(updatedPayment.orderId());
        }

        return updatedPayment;
    }
}
