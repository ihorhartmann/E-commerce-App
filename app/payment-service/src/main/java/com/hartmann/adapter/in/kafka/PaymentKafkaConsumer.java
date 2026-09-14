package com.hartmann.adapter.in.kafka;

import com.hartmann.adapter.common.kafka.KafkaConst;
import com.hartmann.adapter.in.kafka.dto.RefundRequestedCommand;
import com.hartmann.application.port.in.PaymentService;
import com.hartmann.application.port.in.RefundService;
import com.hartmann.application.port.out.idempotency.dto.KafkaMessageMetadata;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import static com.hartmann.adapter.common.kafka.KafkaConst.REFUND_REQUESTED_COMMAND;

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
        var message = new KafkaMessageMetadata(messageId, correlationId, messageType);

        if (REFUND_REQUESTED_COMMAND.equals(messageType)) {
            handleRefundRequested(payload, message);
        } else {
            LOG.info("ignoring unhandled {} messageType={} correlationId={}", KafkaConst.PAYMENT_COMMANDS_TOPIC, messageType, correlationId);
            return;
        }

        LOG.info("successfully processed {} messageType={} correlationId={}", KafkaConst.PAYMENT_COMMANDS_TOPIC, messageType, correlationId);
    }

    private void handleRefundRequested(String payload, KafkaMessageMetadata message) {
        var command = objectMapper.readValue(payload, RefundRequestedCommand.class);
        var payment = paymentService.getByOrderId(command.orderId());
        refundService.submitRefund(message, payment.id(), RefundRequestedCommand.toMoney(command));
    }
}
