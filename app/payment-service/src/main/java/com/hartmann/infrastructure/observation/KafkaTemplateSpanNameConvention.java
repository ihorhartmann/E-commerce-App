package com.hartmann.infrastructure.observation;

import com.hartmann.adapter.common.kafka.KafkaConst;
import io.micrometer.common.KeyValues;
import org.springframework.kafka.support.micrometer.KafkaRecordSenderContext;
import org.springframework.kafka.support.micrometer.KafkaTemplateObservation;

// Names a sent message's span `send payment.events PaymentCompleted` instead of Spring Kafka's `payment.events send`,
// and tags it with the message's type and correlation id.
public class KafkaTemplateSpanNameConvention extends KafkaTemplateObservation.DefaultKafkaTemplateObservationConvention {
    private static final String SEND = "send";

    @Override
    public String getContextualName(KafkaRecordSenderContext context) {
        return SpanNames.messagingSpanName(SEND, context.getDestination(),
                SpanNames.headerValue(context.getRecord().headers(), KafkaConst.MESSAGE_TYPE));
    }

    @Override
    public KeyValues getHighCardinalityKeyValues(KafkaRecordSenderContext context) {
        return super.getHighCardinalityKeyValues(context).and(SpanNames.messageKeyValues(context.getRecord().headers()));
    }
}
