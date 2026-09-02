package com.hartmann.application.service;

import com.hartmann.adapter.out.http.exception.PaymentGatewayException;
import com.hartmann.adapter.out.http.exception.PaymentGatewayUnavailableException;
import com.hartmann.application.port.in.PaymentService;
import com.hartmann.application.port.in.dto.PaymentIntentResult;
import com.hartmann.application.port.out.http.PaymentGateway;
import com.hartmann.application.port.out.http.dto.SubmitPaymentResult;
import com.hartmann.application.port.out.kafka.PaymentKafkaProducer;
import com.hartmann.application.port.out.repository.PaymentRepository;
import com.hartmann.domain.model.Payment;
import com.hartmann.domain.model.PaymentStatus;
import com.hartmann.domain.value.Money;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PaymentServiceImpl implements PaymentService {
    private static final Logger LOG = LoggerFactory.getLogger(PaymentServiceImpl.class);

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

    // Deliberately two separate, non-transactional writes around the gateway call: an external
    // HTTP call must never sit inside a DB transaction, and the INITIATED row must survive even if
    // the process crashes between creating it and calling Stripe - that row is the whole point,
    // it's the durable trace of the attempt (see PaymentRepository.expireOverdue).
    @Override
    public PaymentIntentResult submitPayment(long orderId, Money amount) {
        var initiatedPayment = paymentRepository.create(Payment.createNew(orderId, amount));

        var result = submitToGateway(orderId, amount, initiatedPayment);

        var pendingPayment = paymentRepository.markPending(initiatedPayment.id(), result.externalId());
        return new PaymentIntentResult(pendingPayment, result.clientSecret());
    }

    private SubmitPaymentResult submitToGateway(long orderId, Money amount, Payment initiated) {
        try {
            return paymentGateway.submitPayment(orderId, amount);
        } catch (PaymentGatewayUnavailableException | PaymentGatewayException e) {
            // order-service already learns about this synchronously (the exception propagates
            // straight back through the HTTP call) and cancels the reservation itself, so no Kafka
            // event is published here.
            paymentRepository.updateStatus(initiated.id(), PaymentStatus.FAILED);
            throw e;
        }
    }

    @Override
    @Transactional
    public Payment updateStatusByExternalId(String externalId, PaymentStatus status) {
        var payment = paymentRepository.getByExternalId(externalId);
        var updatedPayment = paymentRepository.updateStatus(payment.id(), status);

        if (status == PaymentStatus.CONFIRMED) {
            paymentKafkaProducer.publishPaymentCompleted(updatedPayment.orderId());
        }
        // ATTEMPT_DECLINED is non-terminal - the customer may still retry the same PaymentIntent
        // with a different payment method, so order-service isn't told until this resolves.

        return updatedPayment;
    }

    @Override
    // TODO: if publishPaymentExpired throws partway through the loop, the payments already marked
    // EXPIRED above are never retried (expireOverdue() won't re-select a terminal row) - their
    // Kafka notification is lost for good. Fix properly via an outbox pattern (write the "expired"
    // fact and its pending Kafka event atomically in the same DB transaction as expireOverdue(),
    // then have a separate poller publish/retry from the outbox) rather than wrapping this in
    // @Transactional, which would hold the DB transaction open across the Kafka calls.
    public void expireOverduePayments() {
        var expired = paymentRepository.expireOverdue();

        for (var payment : expired) {
            LOG.info("expired payment id={} orderId={}", payment.id(), payment.orderId());

            // A null externalId means this payment never left INITIATED - the gateway call itself
            // never succeeded, so order-service already resolved that order synchronously when
            // submitPayment's HTTP call failed. Only PENDING/ATTEMPT_DECLINED-origin expiries are
            // news to order-service, which is still waiting on an outcome for those.
            if (payment.externalId() != null) {
                paymentKafkaProducer.publishPaymentExpired(payment.orderId());
            }
        }
    }
}
