package com.hartmann.application.service;

import com.hartmann.adapter.out.http.exception.PaymentGatewayException;
import com.hartmann.adapter.out.http.exception.PaymentGatewayUnavailableException;
import com.hartmann.application.port.common.kafka.KafkaUtils;
import com.hartmann.application.port.in.RefundService;
import com.hartmann.application.port.out.http.PaymentGateway;
import com.hartmann.application.port.out.http.dto.SubmitRefundResult;
import com.hartmann.application.port.out.idempotency.ProcessedKafkaMessageStore;
import com.hartmann.application.port.out.idempotency.dto.KafkaMessageMetadata;
import com.hartmann.application.port.out.repository.PaymentRepository;
import com.hartmann.application.port.out.repository.RefundRepository;
import com.hartmann.domain.model.Payment;
import com.hartmann.domain.model.Refund;
import com.hartmann.domain.model.RefundStatus;
import com.hartmann.application.port.out.kafka.PaymentKafkaProducer;
import com.hartmann.domain.value.Money;

import java.util.List;

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
    private final ProcessedKafkaMessageStore processedKafkaMessageStore;
    private final TransactionTemplate transactionTemplate;

    public RefundServiceImpl(
            RefundRepository refundRepository,
            PaymentRepository paymentRepository,
            PaymentGateway paymentGateway,
            PaymentKafkaProducer paymentKafkaProducer,
            ProcessedKafkaMessageStore processedKafkaMessageStore,
            PlatformTransactionManager transactionManager
    ) {
        this.refundRepository = refundRepository;
        this.paymentRepository = paymentRepository;
        this.paymentGateway = paymentGateway;
        this.paymentKafkaProducer = paymentKafkaProducer;
        this.processedKafkaMessageStore = processedKafkaMessageStore;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @Override
    public List<Refund> getByOrderId(long orderId) {
        var payment = paymentRepository.getByOrderId(orderId);
        return refundRepository.findByPaymentId(payment.id());
    }

    // Same shape as PaymentServiceImpl.submitPayment: two separate writes around the gateway call,
    // no transaction spanning the external HTTP call, so the INITIATED row survives a crash.
    @Override
    public Refund submitRefund(@Nullable KafkaMessageMetadata message, long paymentId, Money amount) {
        var payment = paymentRepository.getByIdWithRefunds(paymentId);

        var initiatedRefund = createInitiatedRefund(payment, amount, message);
        if (initiatedRefund == null) {
            return null;
        }

        SubmitRefundResult result;
        try {
            result = paymentGateway.submitRefund(payment.externalId(), amount);
        } catch (PaymentGatewayUnavailableException | PaymentGatewayException e) {
            refundRepository.updateStatus(initiatedRefund.id(), RefundStatus.FAILED);
            throw e;
        }

        // Unlike Payment, Stripe often resolves a card pendingRefund synchronously - if it already
        // landed on a terminal status here, publish now rather than waiting for a webhook that may
        // never fire (pendingRefund.updated isn't guaranteed for an object that never changes again).
        var pendingRefund = refundRepository.markPending(initiatedRefund.id(), result.externalId(), result.status());
        var correlationId = message != null ? message.correlationId() : KafkaUtils.formatKafkaId(payment.orderId());
        publishIfTerminal(correlationId, payment.orderId(), pendingRefund);

        return pendingRefund;
    }

    private @Nullable Refund createInitiatedRefund(Payment payment, Money amount, @Nullable KafkaMessageMetadata message) {
        var draftRefund = payment.createRefund(amount);
        if (message == null) {
            return refundRepository.create(draftRefund);
        }

        return transactionTemplate.execute(_ -> {
            boolean alreadyProcessed = processedKafkaMessageStore.markProcessed(message);
            return alreadyProcessed ? null : refundRepository.create(draftRefund);
        });
    }

    @Override
    @Transactional
    public Refund updateStatusByExternalId(String externalId, RefundStatus status) {
        var refund = refundRepository.getByExternalId(externalId);
        var updatedRefund = refundRepository.updateStatus(refund.id(), status);

        if (updatedRefund.status() == RefundStatus.SUCCEEDED || updatedRefund.status() == RefundStatus.FAILED) {
            var payment = paymentRepository.getById(updatedRefund.paymentId());
            var correlationId = KafkaUtils.formatKafkaId(payment.orderId());
            publishIfTerminal(correlationId, payment.orderId(), updatedRefund);
        }

        return updatedRefund;
    }

    private void publishIfTerminal(String correlationId, long orderId, Refund refund) {
        if (refund.status() == RefundStatus.SUCCEEDED) {
            paymentKafkaProducer.publishRefundCompleted(correlationId, orderId, refund.amount());
        } else if (refund.status() == RefundStatus.FAILED) {
            paymentKafkaProducer.publishRefundFailed(correlationId, orderId);
        }
    }
}
