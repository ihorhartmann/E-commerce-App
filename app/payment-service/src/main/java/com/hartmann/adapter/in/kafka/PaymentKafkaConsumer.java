package com.hartmann.adapter.in.kafka;

import com.hartmann.adapter.common.kafka.KafkaConst;
import com.hartmann.adapter.in.kafka.dto.RefundRequestedCommand;
import com.hartmann.adapter.in.kafka.exception.InvalidMessageHeaderException;
import com.hartmann.adapter.in.kafka.exception.NonRetryableException;
import com.hartmann.adapter.out.http.exception.PaymentGatewayException;
import com.hartmann.application.port.in.PaymentService;
import com.hartmann.application.port.in.RefundService;
import com.hartmann.application.port.out.repository.exception.ObjectNotFoundException;
import com.hartmann.application.service.exception.IdempotencyKeyReusedException;
import com.hartmann.domain.exception.DomainException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.UUID;

import static com.hartmann.adapter.common.kafka.KafkaConst.REFUND_REQUESTED_COMMAND;

@Component
public class PaymentKafkaConsumer {
    private static final Logger LOG = LoggerFactory.getLogger(PaymentKafkaConsumer.class);

    // These fail the same way on every attempt, so they're wrapped in NonRetryableException and go
    // straight to the DLT. Anything else (DB errors, PaymentGatewayUnavailableException, ...) is
    // transient and retried.
    private static final List<Class<? extends Exception>> NON_RETRYABLE_EXCEPTIONS = List.of(
            JacksonException.class,              // payload is not valid JSON / doesn't match the command
            InvalidMessageHeaderException.class, // a header is present but unusable (messageId isn't a UUID)
            DomainException.class,               // a business rule rejected the command
            ObjectNotFoundException.class,       // the payment/refund the command refers to doesn't exist
            IdempotencyKeyReusedException.class, // the messageId already belongs to a different refund
            PaymentGatewayException.class        // the payment gateway rejected the request itself
    );

    private final RefundService refundService;
    private final PaymentService paymentService;
    private final ObjectMapper objectMapper;

    public PaymentKafkaConsumer(RefundService refundService, PaymentService paymentService, ObjectMapper objectMapper) {
        this.refundService = refundService;
        this.paymentService = paymentService;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(topics = KafkaConst.PAYMENT_COMMANDS_TOPIC, groupId = "${spring.kafka.consumer.group-id}")
    public void onMessage(
            @Header(KafkaConst.MESSAGE_TYPE) String messageType,
            @Header(KafkaConst.MESSAGE_ID) String messageId,
            @Header(KafkaConst.CORRELATION_ID) String correlationId,
            @Payload String payload
    ) {
        LOG.debug("received {} messageType={} correlationId={}", KafkaConst.PAYMENT_COMMANDS_TOPIC, messageType, correlationId);

        try {
            if (REFUND_REQUESTED_COMMAND.equals(messageType)) {
                handleRefundRequested(payload, messageId, correlationId);
            } else {
                LOG.info("ignoring unhandled {} messageType={} correlationId={}", KafkaConst.PAYMENT_COMMANDS_TOPIC, messageType, correlationId);
                return;
            }
        } catch (RuntimeException e) {
            throw classify(e);
        }

        LOG.info("successfully processed {} messageType={} correlationId={}", KafkaConst.PAYMENT_COMMANDS_TOPIC, messageType, correlationId);
    }

    // The messageId is the refund's idempotency key, so a redelivered command resumes the refund it
    // already created instead of creating another one.
    private void handleRefundRequested(String payload, String messageId, String correlationId) {
        var idempotencyKey = parseMessageId(messageId);
        var command = objectMapper.readValue(payload, RefundRequestedCommand.class);
        var payment = paymentService.getByOrderId(command.orderId());
        refundService.submitRefund(idempotencyKey, correlationId, payment.id(), RefundRequestedCommand.toMoney(command));
    }

    private UUID parseMessageId(String messageId) {
        try {
            return UUID.fromString(messageId);
        } catch (IllegalArgumentException e) {
            throw new InvalidMessageHeaderException("messageId " + messageId + " is not a UUID", e);
        }
    }

    // Wraps e in NonRetryableException if it, or any exception in its cause chain, is one of
    // NON_RETRYABLE_EXCEPTIONS, and returns it unchanged otherwise.
    private static RuntimeException classify(RuntimeException e) {
        for (Throwable cause = e; cause != null; cause = cause.getCause()) {
            var current = cause;
            if (NON_RETRYABLE_EXCEPTIONS.stream().anyMatch(type -> type.isInstance(current))) {
                return new NonRetryableException(e);
            }
        }

        return e;
    }
}
