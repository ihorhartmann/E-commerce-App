package com.hartmann.adapter.in.kafka;

import com.hartmann.adapter.common.kafka.KafkaConst;
import com.hartmann.adapter.in.kafka.dto.RefundRequestedCommand;
import com.hartmann.adapter.in.kafka.exception.InvalidMessageHeaderException;
import com.hartmann.adapter.in.kafka.exception.MalformedPayloadException;
import com.hartmann.application.port.in.PaymentService;
import com.hartmann.application.port.in.RefundService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.spi.LoggingEventBuilder;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.util.UUID;

import static com.hartmann.adapter.common.kafka.KafkaConst.REFUND_REQUESTED_COMMAND;
import static com.hartmann.domain.exception.Attributes.attributesOf;
import static com.hartmann.domain.exception.Attributes.outermostOf;

/**
 * A failure is rethrown as it is: the error handler (KafkaErrorHandlerConfig) retries it or dead-letters it at
 * once, decided by {@link KafkaFailures#isPermanentFailure}.
 */
@Component
public class PaymentKafkaConsumer {
    private static final Logger LOG = LoggerFactory.getLogger(PaymentKafkaConsumer.class);

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
                handleRefundRequested(messageType, payload, messageId, correlationId);
            } else {
                LOG.info("ignoring unhandled {} messageType={} correlationId={}", KafkaConst.PAYMENT_COMMANDS_TOPIC, messageType, correlationId);
                return;
            }
        } catch (RuntimeException e) {
            logFailure(messageType, correlationId, e);
            throw e;
        }

        LOG.info("successfully processed {} messageType={} correlationId={}", KafkaConst.PAYMENT_COMMANDS_TOPIC, messageType, correlationId);
    }

    // The messageId is the refund's idempotency key, so a redelivered command resumes the refund it
    // already created instead of creating another one.
    private void handleRefundRequested(String messageType, String payload, String messageId, String correlationId) {
        var idempotencyKey = parseMessageId(messageId);
        var command = parse(messageType, payload, RefundRequestedCommand.class);
        var payment = paymentService.getByOrderId(command.orderId());
        refundService.submitRefund(idempotencyKey, correlationId, payment.id(), RefundRequestedCommand.toMoney(command));
    }

    private UUID parseMessageId(String messageId) {
        try {
            return UUID.fromString(messageId);
        } catch (IllegalArgumentException e) {
            throw new InvalidMessageHeaderException(KafkaConst.MESSAGE_ID, messageId, e);
        }
    }

    private <T> T parse(String messageType, String payload, Class<T> type) {
        try {
            return objectMapper.readValue(payload, type);
        } catch (JacksonException e) {
            throw new MalformedPayloadException(messageType, e);
        }
    }

    // Logged on every attempt, as the attempt is made. A permanent failure as a warning, since the
    // message is only dead-lettered; anything else as an error with its stack trace.
    private static void logFailure(String messageType, String correlationId, RuntimeException exception) {
        LoggingEventBuilder event;
        if (KafkaFailures.isPermanentFailure(exception)) {
            event = LOG.atWarn().setMessage("Message rejected");
        } else {
            event = LOG.atError().setCause(exception).setMessage("Message handling failed");
        }

        event.addKeyValue("topic", KafkaConst.PAYMENT_COMMANDS_TOPIC)
                .addKeyValue("messageType", messageType)
                .addKeyValue("correlationId", correlationId)
                .addKeyValue("exceptionMessage", exception.getMessage());
        outermostOf(exception).ifPresent(found -> event.addKeyValue("code", found.code().code()));
        attributesOf(exception).forEach(event::addKeyValue);
        event.log();
    }
}
