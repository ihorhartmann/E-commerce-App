package com.hartmann.application.service;

import com.hartmann.adapter.out.http.exception.PaymentGatewayException;
import com.hartmann.adapter.out.http.exception.PaymentGatewayUnavailableException;
import com.hartmann.application.port.in.RefundService;
import com.hartmann.application.port.out.http.PaymentGateway;
import com.hartmann.application.port.out.http.dto.SubmitRefundResult;
import com.hartmann.application.port.out.repository.PaymentRepository;
import com.hartmann.application.port.out.repository.RefundRepository;
import com.hartmann.domain.model.Refund;
import com.hartmann.domain.model.RefundStatus;
import com.hartmann.application.port.out.kafka.PaymentKafkaProducer;
import com.hartmann.domain.value.Money;

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

    // Same shape as PaymentServiceImpl.submitPayment: two separate writes around the gateway call,
    // no transaction spanning the external HTTP call, so the INITIATED row survives a crash.
    @Override
    public Refund submitRefund(long paymentId, Money amount) {
        var payment = paymentRepository.getByIdWithRefunds(paymentId);
        var draftRefund = payment.createRefund(amount);
        var initiatedRefund = refundRepository.create(draftRefund);

        var result = submitToGateway(payment.externalId(), amount, initiatedRefund);

        // Unlike Payment, Stripe often resolves a card pendingRefund synchronously - if it already
        // landed on a terminal status here, publish now rather than waiting for a webhook that may
        // never fire (pendingRefund.updated isn't guaranteed for an object that never changes again).
        var pendingRefund = refundRepository.markPending(initiatedRefund.id(), result.externalId(), result.status());
        publishIfTerminal(payment.orderId(), pendingRefund);

        return pendingRefund;
    }

    private SubmitRefundResult submitToGateway(String paymentExternalId, Money amount, Refund initiated) {
        try {
            return paymentGateway.submitRefund(paymentExternalId, amount);
        } catch (PaymentGatewayUnavailableException | PaymentGatewayException e) {
            refundRepository.updateStatus(initiated.id(), RefundStatus.FAILED);
            throw e;
        }
    }

    @Override
    @Transactional
    public Refund updateStatusByExternalId(String externalId, RefundStatus status) {
        var refund = refundRepository.getByExternalId(externalId);
        var updatedRefund = refundRepository.updateStatus(refund.id(), status);

        if (updatedRefund.status() == RefundStatus.SUCCEEDED || updatedRefund.status() == RefundStatus.FAILED) {
            var payment = paymentRepository.getById(updatedRefund.paymentId());
            publishIfTerminal(payment.orderId(), updatedRefund);
        }

        return updatedRefund;
    }

    private void publishIfTerminal(long orderId, Refund refund) {
        if (refund.status() == RefundStatus.SUCCEEDED) {
            paymentKafkaProducer.publishRefundCompleted(orderId, refund.amount());
        } else if (refund.status() == RefundStatus.FAILED) {
            paymentKafkaProducer.publishRefundFailed(orderId);
        }
    }
}
