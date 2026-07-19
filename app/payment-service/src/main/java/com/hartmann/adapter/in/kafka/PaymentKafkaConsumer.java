package com.hartmann.adapter.in.kafka;

import com.hartmann.adapter.common.kafka.KafkaConst;
import com.hartmann.adapter.in.kafka.dto.RefundRequestedMessage;
import com.hartmann.application.port.in.PaymentService;
import com.hartmann.application.port.in.RefundService;
import com.hartmann.domain.value.Currency;
import com.hartmann.domain.value.Money;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import static com.hartmann.adapter.common.kafka.KafkaConst.REFUND_REQUESTED_EVENT_TYPE;

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
            @Header(KafkaConst.EVENT_TYPE) String eventType,
            @Header(KafkaConst.CORRELATION_ID) String correlationId,
            @Payload String payload
    ) {
        LOG.debug("received {} eventType={} correlationId={}", KafkaConst.PAYMENT_COMMANDS_TOPIC, eventType, correlationId);

        if (REFUND_REQUESTED_EVENT_TYPE.equals(eventType)) {
            handleRefundRequested(payload);
        } else {
            LOG.info("ignoring unhandled {} eventType={} correlationId={}", KafkaConst.PAYMENT_COMMANDS_TOPIC, eventType, correlationId);
            return;
        }

        LOG.info("successfully processed {} eventType={} correlationId={}", KafkaConst.PAYMENT_COMMANDS_TOPIC, eventType, correlationId);
    }

    //TODO getPayment should be done via repo, not here
    private void handleRefundRequested(String payload) {
        var message = objectMapper.readValue(payload, RefundRequestedMessage.class);
        var amount = new Money(message.amount(), Currency.fromCode(message.currency()));
        var payment = paymentService.getByOrderId(message.orderId());
        refundService.submitRefund(payment.id(), amount);
    }
}
