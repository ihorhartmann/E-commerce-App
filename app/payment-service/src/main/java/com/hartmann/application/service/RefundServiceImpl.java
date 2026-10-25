package com.hartmann.application.service;

import com.hartmann.application.port.common.kafka.KafkaUtils;
import com.hartmann.application.port.in.RefundService;
import com.hartmann.application.port.out.http.PaymentGateway;
import com.hartmann.application.port.out.http.dto.SubmitRefundResult;
import com.hartmann.application.port.out.http.exception.PaymentGatewayRejectedException;
import com.hartmann.application.port.out.kafka.PaymentKafkaProducer;
import com.hartmann.application.port.out.repository.PaymentRepository;
import com.hartmann.application.port.out.repository.RefundRepository;
import com.hartmann.application.port.out.repository.exception.DuplicateIdempotencyKeyException;
import com.hartmann.application.service.exception.ConflictingRefundNotFoundException;
import com.hartmann.application.service.exception.RefundIdempotencyKeyReusedException;
import com.hartmann.domain.model.Refund;
import com.hartmann.domain.model.RefundStatus;
import com.hartmann.domain.value.Money;

import java.util.List;
import java.util.UUID;

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
    public Refund submitRefund(UUID idempotencyKey, String correlationId, long paymentId, Money amount) {
        var payment = paymentRepository.getById(paymentId);

        var refund = resolveRefund(paymentId, idempotencyKey, amount);
        if (refund.isSubmitted()) {
            return refund;
        }

        // A PaymentGatewayUnavailableException is left to propagate with the refund still INITIATED:
        // the retry resumes it and resubmits with the same idempotency key, which the gateway answers
        // with its original result if the first call did go through.
        SubmitRefundResult result;
        try {
            result = paymentGateway.submitRefund(payment.externalId(), refund);
        } catch (PaymentGatewayRejectedException e) {
            refund.fail();
            transactionTemplate.executeWithoutResult(_ -> {
                refundRepository.updateStatus(refund.id(), refund.version(), refund.status());
                // reloaded: creating the refund already bumped the version of the payment loaded above
                var currentPayment = paymentRepository.getById(refund.paymentId());
                currentPayment.releaseRefund(refund);
                paymentRepository.updateRefundedAmount(currentPayment);
                paymentKafkaProducer.publishRefundFailed(correlationId, payment.orderId());
            });
            throw e;
        }

        // Unlike Payment, Stripe often resolves a card refund synchronously - if it already landed on a
        // terminal status here, publish now rather than waiting for a webhook
        refund.submit(result.externalId(), result.status());
        return transactionTemplate.execute(_ -> {
            if (refund.status() == RefundStatus.SUCCEEDED) {
                paymentKafkaProducer.publishRefundCompleted(correlationId, payment.orderId(), refund.amount());
            } else if (refund.status() == RefundStatus.FAILED) {
                // reloaded: creating the refund already bumped the version of the payment loaded above
                var currentPayment = paymentRepository.getById(refund.paymentId());
                currentPayment.releaseRefund(refund);
                paymentRepository.updateRefundedAmount(currentPayment);
                paymentKafkaProducer.publishRefundFailed(correlationId, payment.orderId());
            }
            // stored last, so the refund as the database now holds it is what's returned
            return refundRepository.markPending(refund.id(), refund.version(), refund.externalId(), refund.status());
        });
    }

    private Refund resolveRefund(long paymentId, UUID idempotencyKey, Money amount) {
        var refund = refundRepository.findByIdempotencyKey(idempotencyKey)
                .orElseGet(() -> createRefund(paymentId, idempotencyKey, amount));

        if (!refund.matchesPaymentIdAndAmount(paymentId, amount)) {
            throw new RefundIdempotencyKeyReusedException(idempotencyKey, refund.id());
        }

        return refund;
    }

    // Stores the new refund and the payment's reduced refundable balance in one transaction, so the
    // balance never counts a refund that doesn't exist, or the other way round.
    private Refund createRefund(long paymentId, UUID idempotencyKey, Money amount) {
        try {
            return transactionTemplate.execute(_ -> {
                var payment = paymentRepository.getById(paymentId);
                var refund = refundRepository.create(payment.requestRefund(amount, idempotencyKey));
                paymentRepository.updateRefundedAmount(payment);
                return refund;
            });
        } catch (DuplicateIdempotencyKeyException e) {
            // Lost the race to a concurrent request with the same key, which inserted its refund
            // between the find and the create above - resume that one instead. This transaction rolled
            // back, so its balance change is gone too. The conflicting row is committed by the time
            // create returns and refunds are never deleted, so it's always found.
            return refundRepository.findByIdempotencyKey(idempotencyKey).orElseThrow(() -> {
                var notFound = new ConflictingRefundNotFoundException(idempotencyKey);
                // suppressed, not the cause: as the cause, the conflict would be reported instead of this
                // internal failure (the error handlers report the outermost exception of ours in the chain)
                notFound.addSuppressed(e);
                return notFound;
            });
        }
    }

    @Override
    @Transactional
    public Refund updateStatusByExternalId(String externalId, RefundStatus status) {
        var refund = refundRepository.getByExternalId(externalId);
        if (!refund.applyGatewayStatus(status)) {
            // a redelivered event reporting the status the refund already has
            return refund;
        }

        refund = refundRepository.updateStatus(refund.id(), refund.version(), refund.status());

        // A terminal status is final, so a refund that just changed into one got there for the first time.
        if (refund.status().isTerminal()) {
            var payment = paymentRepository.getById(refund.paymentId());
            var correlationId = KafkaUtils.formatKafkaId(payment.orderId());
            if (refund.status() == RefundStatus.SUCCEEDED) {
                paymentKafkaProducer.publishRefundCompleted(correlationId, payment.orderId(), refund.amount());
            } else {
                payment.releaseRefund(refund);
                paymentRepository.updateRefundedAmount(payment);
                paymentKafkaProducer.publishRefundFailed(correlationId, payment.orderId());
            }
        }

        return refund;
    }
}
