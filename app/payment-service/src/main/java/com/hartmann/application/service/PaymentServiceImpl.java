package com.hartmann.application.service;

import com.hartmann.application.port.common.kafka.KafkaUtils;
import com.hartmann.application.port.in.PaymentService;
import com.hartmann.application.port.in.dto.PaymentIntentResult;
import com.hartmann.application.port.out.http.PaymentGateway;
import com.hartmann.application.port.out.http.dto.PaymentCancellationReason;
import com.hartmann.application.port.out.http.dto.PaymentIntentState;
import com.hartmann.application.port.out.http.dto.SubmitPaymentResult;
import com.hartmann.application.port.out.http.exception.PaymentGatewayRejectedException;
import com.hartmann.application.port.out.http.exception.PaymentGatewayUnavailableException;
import com.hartmann.application.port.out.kafka.PaymentKafkaProducer;
import com.hartmann.application.port.out.repository.PaymentRepository;
import com.hartmann.application.port.out.tracing.CorrelationTagger;
import com.hartmann.application.service.exception.PaymentAlreadyConfirmedException;
import com.hartmann.application.service.exception.PaymentProcessingException;
import com.hartmann.domain.exception.Attributes;
import com.hartmann.domain.model.Payment;
import com.hartmann.domain.model.PaymentStatus;
import com.hartmann.domain.value.Money;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.LinkedHashMap;

@Service
public class PaymentServiceImpl implements PaymentService {
    private static final Logger LOG = LoggerFactory.getLogger(PaymentServiceImpl.class);

    private final PaymentRepository paymentRepository;
    private final PaymentGateway paymentGateway;
    private final PaymentKafkaProducer paymentKafkaProducer;
    private final CorrelationTagger correlationTagger;
    private final TransactionTemplate transactionTemplate;

    public PaymentServiceImpl(
            PaymentRepository paymentRepository,
            PaymentGateway paymentGateway,
            PaymentKafkaProducer paymentKafkaProducer,
            CorrelationTagger correlationTagger,
            PlatformTransactionManager transactionManager
    ) {
        this.paymentRepository = paymentRepository;
        this.paymentGateway = paymentGateway;
        this.paymentKafkaProducer = paymentKafkaProducer;
        this.correlationTagger = correlationTagger;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @Override
    public Payment getByOrderId(long orderId) {
        return paymentRepository.getByOrderId(orderId);
    }

    // Deliberately two separate, non-transactional writes around the gateway call: an external
    // HTTP call must never sit inside a DB transaction, and the INITIATED row must survive even if
    // the process crashes between creating it and calling Stripe - that row is the whole point,
    // it's the durable trace of the attempt (see PaymentRepository.findOverdue).
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
        } catch (PaymentGatewayUnavailableException | PaymentGatewayRejectedException e) {
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
        // a webhook brings no correlation id: the payment's order is its saga's, so it is derived from it
        var correlationId = KafkaUtils.formatKafkaId(payment.orderId());
        correlationTagger.tag(correlationId);
        if (!payment.applyGatewayStatus(status)) {
            // a redelivered event reporting the status the payment already has
            return payment;
        }

        payment = paymentRepository.updateStatus(payment.id(), payment.version(), payment.status());

        if (payment.status() == PaymentStatus.CONFIRMED) {
            paymentKafkaProducer.publishPaymentCompleted(correlationId, payment.orderId());
        }
        // ATTEMPT_DECLINED is non-terminal - the customer may still retry the same PaymentIntent
        // with a different payment method, so order-service isn't told until this resolves.

        return payment;
    }

    // The gateway is called outside any transaction - an external call never sits inside one - and before the payment
    // is changed: once the payment says CANCELLED, its PaymentIntent must no longer be payable. If the payment's update
    // fails after the gateway cancelled it, a repeated request finds it already cancelled there and finishes the job;
    // without one, the expiration sweep does.
    @Override
    public Payment cancelPayment(String correlationId, long orderId) {
        var payment = paymentRepository.getByOrderId(orderId);
        if (payment.isConfirmed()) {
            throw new PaymentAlreadyConfirmedException(orderId);
        }
        if (!payment.isOpen()) {
            // cancelled already - a repeated request - or expired or failed: it can no longer be paid either way, and
            // order-service is told by whatever closed it
            return payment;
        }

        // one that never got a PaymentIntent has nothing to cancel at the gateway
        if (payment.wasSubmitted()) {
            var state = paymentGateway.cancelPayment(orderId, payment.externalId(),
                    PaymentCancellationReason.REQUESTED_BY_CUSTOMER);
            if (state == PaymentIntentState.SUCCEEDED) {
                // the customer paid it before it could be cancelled, and the webhook hasn't told yet
                saveConfirmation(payment.id(), correlationId);
                throw new PaymentAlreadyConfirmedException(orderId);
            }
            if (state == PaymentIntentState.PROCESSING) {
                throw new PaymentProcessingException(orderId);
            }
        }

        return transactionTemplate.execute(_ -> {
            // reloaded: the payment loaded above may have changed while the gateway was called
            var currentPayment = paymentRepository.getById(payment.id());
            if (!currentPayment.isOpen()) {
                // the expiration sweep got there first
                return currentPayment;
            }
            currentPayment.cancel();
            paymentKafkaProducer.publishPaymentCancelled(correlationId, currentPayment.orderId());
            return paymentRepository.updateStatus(currentPayment.id(), currentPayment.version(), currentPayment.status());
        });
    }

    // Each payment on its own: one that fails - the gateway unavailable, a concurrent update - doesn't hold the others
    // back, and stays open for the next run to try again.
    @Override
    public void expireOverduePayments() {
        for (var payment : paymentRepository.findOverdue()) {
            // the payment's order is its saga's correlation id; logged with each line only, not in the log context,
            // which would carry it on to the next payment of the sweep
            var correlationId = KafkaUtils.formatKafkaId(payment.orderId());
            try {
                expire(payment, correlationId);
            } catch (RuntimeException e) {
                logExpirationFailure(payment, correlationId, e);
            }
        }
    }

    private void expire(Payment payment, String correlationId) {
        // One that never got a PaymentIntent has nothing to cancel at the gateway. Cancelled before the payment is
        // expired, so an expired payment can no longer be paid; if expiring it fails after the gateway cancelled it, the
        // next run finds it already cancelled there and finishes the job.
        if (payment.wasSubmitted()) {
            var state = paymentGateway.cancelPayment(payment.orderId(), payment.externalId(),
                    PaymentCancellationReason.EXPIRED);
            if (state == PaymentIntentState.SUCCEEDED) {
                // the customer paid it at the last moment, and the webhook hasn't told yet
                saveConfirmation(payment.id(), correlationId);
                return;
            }
            if (state == PaymentIntentState.PROCESSING) {
                LOG.atInfo().setMessage("overdue payment still processing")
                        .addKeyValue("paymentId", payment.id())
                        .addKeyValue("orderId", payment.orderId())
                        .addKeyValue("correlationId", correlationId)
                        .log();
                return;
            }
        }

        transactionTemplate.executeWithoutResult(_ -> {
            // reloaded: the payment loaded above may have changed while the gateway was called
            var current = paymentRepository.getById(payment.id());
            if (!current.isOpen()) {
                // the customer cancelled it, or another replica's sweep expired it, in the meantime
                return;
            }
            current.expire();
            paymentRepository.updateStatus(current.id(), current.version(), current.status());
            LOG.atInfo().setMessage("expired payment")
                    .addKeyValue("paymentId", current.id())
                    .addKeyValue("orderId", current.orderId())
                    .addKeyValue("correlationId", correlationId)
                    .log();

            // A payment that was never submitted never left INITIATED - the gateway call itself
            // never succeeded, so order-service already resolved that order synchronously when
            // submitPayment's HTTP call failed. Only PENDING/ATTEMPT_DECLINED-origin expiries are
            // news to order-service, which is still waiting on an outcome for those.
            if (current.wasSubmitted()) {
                paymentKafkaProducer.publishPaymentExpired(correlationId, current.orderId());
            }
        });
    }

    // The gateway said the payment is paid. Recorded as its webhook would - which may have done it in the meantime.
    private void saveConfirmation(long paymentId, String correlationId) {
        transactionTemplate.executeWithoutResult(_ -> {
            var payment = paymentRepository.getById(paymentId);
            if (!payment.applyGatewayStatus(PaymentStatus.CONFIRMED)) {
                return;
            }
            paymentRepository.updateStatus(payment.id(), payment.version(), payment.status());
            paymentKafkaProducer.publishPaymentCompleted(correlationId, payment.orderId());
        });
    }

    // A WARN, not an ERROR: the payment stays open, and the next run tries it again. Its attributes are added only
    // under keys this line doesn't have already - Spring Boot's structured logging drops a line with a key twice.
    private static void logExpirationFailure(Payment payment, String correlationId, RuntimeException exception) {
        var keyValues = new LinkedHashMap<String, Object>();
        keyValues.put("paymentId", payment.id());
        keyValues.put("orderId", payment.orderId());
        keyValues.put("correlationId", correlationId);
        keyValues.put("exceptionMessage", exception.getMessage());
        Attributes.outermostOf(exception).ifPresent(found -> keyValues.put("code", found.code().code()));
        Attributes.attributesOf(exception).forEach(keyValues::putIfAbsent);

        var event = LOG.atWarn().setMessage("payment expiration failed").setCause(exception);
        keyValues.forEach(event::addKeyValue);
        event.log();
    }
}
