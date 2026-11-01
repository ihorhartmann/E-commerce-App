package com.hartmann.adapter.in.kafka;

import com.hartmann.adapter.common.kafka.KafkaConst;
import com.hartmann.adapter.in.kafka.dto.RefundRequestedCommand;
import com.hartmann.adapter.in.kafka.exception.InvalidMessageHeaderException;
import com.hartmann.adapter.in.kafka.exception.MalformedPayloadException;
import com.hartmann.application.port.in.PaymentService;
import com.hartmann.application.port.in.RefundService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.slf4j.spi.LoggingEventBuilder;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.UUID;

import static com.hartmann.adapter.common.kafka.KafkaConst.REFUND_REQUESTED_COMMAND;

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
            @Header(value = KafkaConst.MESSAGE_TYPE, required = false) String messageType,
            @Header(value = KafkaConst.MESSAGE_ID, required = false) String messageId,
            @Header(value = KafkaConst.CORRELATION_ID, required = false) String correlationId,
            @Payload String payload
    ) {
        // The message's ids are in the MDC while it is handled, so every line logged meanwhile - here and in the
        // services - carries them as fields of its own. The headers are checked in handleMessage rather than by @Header
        // binding, so a message missing one is rejected like any other invalid message - logged, with our code, and
        // dead-lettered.
        var headers = new LinkedHashMap<String, String>();
        headers.put(KafkaConst.MESSAGE_TYPE, messageType);
        headers.put(KafkaConst.MESSAGE_ID, messageId);
        headers.put(KafkaConst.CORRELATION_ID, correlationId);
        headers.forEach((header, value) -> {
            if (value != null) {
                MDC.put(header, value);
            }
        });
        try {
            handleMessage(messageType, messageId, correlationId, payload);
        } finally {
            headers.keySet().forEach(MDC::remove);
        }
    }

    private void handleMessage(String messageType, String messageId, String correlationId, String payload) {
        LOG.atDebug().setMessage("received kafka message").addKeyValue("topic", KafkaConst.PAYMENT_COMMANDS_TOPIC).log();

        try {
            // in the order the Go services check them in
            var type = requireHeader(KafkaConst.MESSAGE_TYPE, messageType);
            var idempotencyKey = parseMessageId(requireHeader(KafkaConst.MESSAGE_ID, messageId));
            var correlation = requireHeader(KafkaConst.CORRELATION_ID, correlationId);

            if (REFUND_REQUESTED_COMMAND.equals(type)) {
                handleRefundRequested(type, payload, idempotencyKey, correlation);
            } else {
                LOG.atInfo().setMessage("ignoring unhandled kafka message").addKeyValue("topic", KafkaConst.PAYMENT_COMMANDS_TOPIC).log();
                return;
            }
        } catch (RuntimeException e) {
            logFailure(e);
            throw e;
        }

        LOG.atInfo().setMessage("successfully processed kafka message").addKeyValue("topic", KafkaConst.PAYMENT_COMMANDS_TOPIC).log();
    }

    // The messageId is the refund's idempotency key, so a redelivered command resumes the refund it
    // already created instead of creating another one.
    private void handleRefundRequested(String messageType, String payload, UUID idempotencyKey, String correlationId) {
        var command = parse(messageType, payload, RefundRequestedCommand.class);
        var payment = paymentService.getByOrderId(command.orderId());
        refundService.submitRefund(idempotencyKey, correlationId, payment.id(), RefundRequestedCommand.toMoney(command));
    }

    private static String requireHeader(String header, String value) {
        if (value == null) {
            throw InvalidMessageHeaderException.missing(header);
        }
        return value;
    }

    private UUID parseMessageId(String messageId) {
        try {
            return UUID.fromString(messageId);
        } catch (IllegalArgumentException e) {
            throw InvalidMessageHeaderException.withInvalidValue(KafkaConst.MESSAGE_ID, messageId, e);
        }
    }

    private <T> T parse(String messageType, String payload, Class<T> type) {
        try {
            return objectMapper.readValue(payload, type);
        } catch (JacksonException e) {
            throw new MalformedPayloadException(messageType, e);
        }
    }

    // Logged on every attempt, as the attempt is made, as a warning: a permanent failure is rejected - dead-lettered at
    // once - a transient one retried, with its stack trace. The error is logged once, when the message is dead-lettered
    // (RetryableDeadLetterPublishingRecoverer).
    private static void logFailure(RuntimeException exception) {
        LoggingEventBuilder event;
        if (KafkaFailures.isPermanentFailure(exception)) {
            event = LOG.atWarn().setMessage("kafka message rejected");
        } else {
            event = LOG.atWarn().setCause(exception).setMessage("kafka message handling failed");
        }

        event.addKeyValue("topic", KafkaConst.PAYMENT_COMMANDS_TOPIC);
        KafkaFailures.failureKeyValues(exception).forEach(event::addKeyValue);
        event.log();
    }
}
