package com.hartmann.application.service;

import com.hartmann.adapter.out.http.exception.PaymentGatewayException;
import com.hartmann.adapter.out.http.exception.PaymentGatewayUnavailableException;
import com.hartmann.application.port.common.kafka.KafkaUtils;
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
        var payment = paymentRepository.create(Payment.createNew(orderId, amount));

        var result = submitToGateway(orderId, amount, payment);

        payment.markPending(result.externalId());
        payment = paymentRepository.markPending(payment.id(), payment.version(), payment.externalId());
        return new PaymentIntentResult(payment, result.clientSecret());
    }

    private SubmitPaymentResult submitToGateway(long orderId, Money amount, Payment payment) {
        try {
            return paymentGateway.submitPayment(orderId, amount);
        } catch (PaymentGatewayUnavailableException | PaymentGatewayException e) {
            // order-service already learns about this synchronously (the exception propagates
            // straight back through the HTTP call) and cancels the reservation itself, so no Kafka
            // event is published here.
            payment.fail();
            paymentRepository.updateStatus(payment.id(), payment.version(), payment.status());
            throw e;
        }
    }

    @Override
    @Transactional
    public Payment updateStatusByExternalId(String externalId, PaymentStatus status) {
        var payment = paymentRepository.getByExternalId(externalId);
        if (!payment.applyGatewayStatus(status)) {
            // a redelivered event reporting the status the payment already has
            return payment;
        }

        payment = paymentRepository.updateStatus(payment.id(), payment.version(), payment.status());

        if (payment.status() == PaymentStatus.CONFIRMED) {
            var correlationId = KafkaUtils.formatKafkaId(payment.orderId());
            paymentKafkaProducer.publishPaymentCompleted(correlationId, payment.orderId());
        }
        // ATTEMPT_DECLINED is non-terminal - the customer may still retry the same PaymentIntent
        // with a different payment method, so order-service isn't told until this resolves.

        return payment;
    }

    @Override
    @Transactional
    public void expireOverduePayments() {
        var expired = paymentRepository.expireOverdue();

        for (var payment : expired) {
            LOG.info("expired payment id={} orderId={}", payment.id(), payment.orderId());

            // A payment that was never submitted never left INITIATED - the gateway call itself
            // never succeeded, so order-service already resolved that order synchronously when
            // submitPayment's HTTP call failed. Only PENDING/ATTEMPT_DECLINED-origin expiries are
            // news to order-service, which is still waiting on an outcome for those.
            if (payment.wasSubmitted()) {
                var correlationId = KafkaUtils.formatKafkaId(payment.orderId());
                paymentKafkaProducer.publishPaymentExpired(correlationId, payment.orderId());
            }
        }
    }
}
