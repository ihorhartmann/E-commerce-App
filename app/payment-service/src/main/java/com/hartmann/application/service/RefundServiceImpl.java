package com.hartmann.application.service;

import com.hartmann.adapter.out.http.exception.PaymentGatewayException;
import com.hartmann.application.port.common.kafka.KafkaUtils;
import com.hartmann.application.port.in.RefundService;
import com.hartmann.application.port.out.http.PaymentGateway;
import com.hartmann.application.port.out.http.dto.SubmitRefundResult;
import com.hartmann.application.port.out.kafka.PaymentKafkaProducer;
import com.hartmann.application.port.out.repository.PaymentRepository;
import com.hartmann.application.port.out.repository.RefundRepository;
import com.hartmann.application.port.out.repository.exception.DuplicateIdempotencyKeyException;
import com.hartmann.application.service.exception.IdempotencyKeyReusedException;
import com.hartmann.domain.model.Payment;
import com.hartmann.domain.model.Refund;
import com.hartmann.domain.model.RefundStatus;
import com.hartmann.domain.value.Money;

import java.util.List;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class RefundServiceImpl implements RefundService {
    private final RefundRepository refundRepository;
    private final PaymentRepository paymentRepository;
    private final PaymentGateway paymentGateway;
    private final PaymentKafkaProducer paymentKafkaProducer;
    private final TransactionTemplate transactionTemplate;

    public RefundServiceImpl(
            RefundRepository refundRepository,
            PaymentRepository paymentRepository,
            PaymentGateway paymentGateway,
            PaymentKafkaProducer paymentKafkaProducer,
            PlatformTransactionManager transactionManager
    ) {
        this.refundRepository = refundRepository;
        this.paymentRepository = paymentRepository;
        this.paymentGateway = paymentGateway;
        this.paymentKafkaProducer = paymentKafkaProducer;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @Override
    public List<Refund> getByOrderId(long orderId) {
        var payment = paymentRepository.getByOrderId(orderId);
        return refundRepository.findByPaymentId(payment.id());
    }

    // Two separate writes around the gateway call, with no transaction spanning the external HTTP
    // call: the INITIATED row is committed first, so a retry of the same request finds it and resumes
    // it. The refund stays INITIATED until the gateway gives a definitive answer.
    @Override
    public Refund submitRefund(UUID idempotencyKey, @Nullable String correlationId, long paymentId, Money amount) {
        var payment = paymentRepository.getByIdWithRefunds(paymentId);

        var refund = resolveRefund(payment, idempotencyKey, amount);
        if (refund.status() != RefundStatus.INITIATED) {
            return refund;
        }

        var eventCorrelationId = correlationId != null ? correlationId : KafkaUtils.formatKafkaId(payment.orderId());

        // A PaymentGatewayUnavailableException is left to propagate with the refund still INITIATED:
        // the retry resumes it and resubmits with the same idempotency key, which the gateway answers
        // with its original result if the first call did go through.
        SubmitRefundResult result;
        try {
            result = paymentGateway.submitRefund(payment.externalId(), refund);
        } catch (PaymentGatewayException e) {
            transactionTemplate.executeWithoutResult(_ -> {
                refundRepository.updateStatus(refund.id(), RefundStatus.FAILED);
                paymentKafkaProducer.publishRefundFailed(eventCorrelationId, payment.orderId());
            });
            throw e;
        }

        // Unlike Payment, Stripe often resolves a card refund synchronously - if it already landed on a
        // terminal status here, publish now rather than waiting for a webhook
        return transactionTemplate.execute(_ -> {
            var submittedRefund = refundRepository.markPending(refund.id(), result.externalId(), result.status());
            if (submittedRefund.status() == RefundStatus.SUCCEEDED) {
                paymentKafkaProducer.publishRefundCompleted(eventCorrelationId, payment.orderId(), submittedRefund.amount());
            } else if (submittedRefund.status() == RefundStatus.FAILED) {
                paymentKafkaProducer.publishRefundFailed(eventCorrelationId, payment.orderId());
            }
            return submittedRefund;
        });
    }

    private Refund resolveRefund(Payment payment, UUID idempotencyKey, Money amount) {
        var refund = refundRepository.findByIdempotencyKey(idempotencyKey).orElseGet(() -> {
            try {
                return refundRepository.create(payment.createRefund(amount, idempotencyKey));
            } catch (DuplicateIdempotencyKeyException e) {
                // Lost the race to a concurrent request with the same key, which inserted its refund
                // between the find and the create above - resume that one instead. The conflicting row is
                // committed by the time create returns and refunds are never deleted, so it's always found.
                return refundRepository.findByIdempotencyKey(idempotencyKey).orElseThrow(() -> new IllegalStateException(
                        "Refund with idempotency key " + idempotencyKey + " conflicted on insert but can't be found"));
            }
        });

        if (!refund.matchesPaymentIdAndAmount(payment.id(), amount)) {
            throw new IdempotencyKeyReusedException(
                    "Idempotency key " + idempotencyKey + " was already used for refund " + refund.id());
        }

        return refund;
    }

    @Override
    @Transactional
    public Refund updateStatusByExternalId(String externalId, RefundStatus status) {
        var refund = refundRepository.getByExternalId(externalId);
        var wasTerminal = refund.status().isTerminal();
        var updatedRefund = refundRepository.updateStatus(refund.id(), status);

        if (!wasTerminal && updatedRefund.status().isTerminal()) {
            var payment = paymentRepository.getById(updatedRefund.paymentId());
            var correlationId = KafkaUtils.formatKafkaId(payment.orderId());
            if (updatedRefund.status() == RefundStatus.SUCCEEDED) {
                paymentKafkaProducer.publishRefundCompleted(correlationId, payment.orderId(), updatedRefund.amount());
            } else {
                paymentKafkaProducer.publishRefundFailed(correlationId, payment.orderId());
            }
        }

        return updatedRefund;
    }
}
