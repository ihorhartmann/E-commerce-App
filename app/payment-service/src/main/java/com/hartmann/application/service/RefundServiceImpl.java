package com.hartmann.application.service;

import com.hartmann.application.port.in.RefundService;
import com.hartmann.application.port.out.http.PaymentGateway;
import com.hartmann.application.port.out.kafka.PaymentKafkaProducer;
import com.hartmann.application.port.out.repository.PaymentRepository;
import com.hartmann.application.port.out.repository.RefundRepository;
import com.hartmann.domain.model.Payment;
import com.hartmann.domain.model.Refund;
import com.hartmann.domain.model.RefundStatus;
import com.hartmann.domain.value.Money;

import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RefundServiceImpl implements RefundService {
    private final RefundRepository refundRepository;
    private final PaymentRepository paymentRepository;
    private final PaymentGateway paymentGateway;
    private final PaymentKafkaProducer paymentKafkaProducer;

    public RefundServiceImpl(
            RefundRepository refundRepository,
            PaymentRepository paymentRepository,
            PaymentGateway paymentGateway,
            PaymentKafkaProducer paymentKafkaProducer
    ) {
        this.refundRepository = refundRepository;
        this.paymentRepository = paymentRepository;
        this.paymentGateway = paymentGateway;
        this.paymentKafkaProducer = paymentKafkaProducer;
    }

    @Override
    public List<Refund> getByOrderId(long orderId) {
        var payment = paymentRepository.getByOrderId(orderId);
        return refundRepository.findByPaymentId(payment.id());
    }

    @Override
    @Transactional
    public Refund submitRefund(long paymentId, Money amount) {
        var payment = getPaymentWithRefunds(paymentId);
        var draftRefund = payment.createRefund(amount);

        var result = paymentGateway.submitRefund(payment.externalId(), amount);

        var refund = new Refund(
                draftRefund.id(), draftRefund.paymentId(), result.externalId(), draftRefund.amount(), result.status(),
                draftRefund.createdAt(), Instant.now()
        );
        return refundRepository.create(refund);
    }

    @Override
    @Transactional
    public Refund updateStatusByExternalId(String externalId, RefundStatus status) {
        var refund = refundRepository.getByExternalId(externalId);
        var updated = refundRepository.updateStatus(refund.id(), status);

        if (updated.status() == RefundStatus.SUCCEEDED) {
            var payment = paymentRepository.getById(updated.paymentId());
            paymentKafkaProducer.publishRefundCompleted(payment.orderId(), updated.paymentId(), updated.amount());
        }

        return updated;
    }

    //TODO replace it with join in real postgresql repo
    private Payment getPaymentWithRefunds(long paymentId) {
        var payment = paymentRepository.getById(paymentId);
        var refunds = refundRepository.findByPaymentId(paymentId);
        return new Payment(
                payment.id(),
                payment.orderId(),
                payment.externalId(), payment.amount(),
                payment.status(),
                refunds,
                payment.createdAt(),
                payment.updatedAt()
        );
    }
}
