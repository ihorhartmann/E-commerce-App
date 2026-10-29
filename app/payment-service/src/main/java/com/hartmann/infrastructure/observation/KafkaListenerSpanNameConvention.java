package com.hartmann.infrastructure.observation;

import com.hartmann.adapter.common.kafka.KafkaConst;
import io.micrometer.common.KeyValues;
import org.springframework.kafka.support.micrometer.KafkaListenerObservation;
import org.springframework.kafka.support.micrometer.KafkaRecordReceiverContext;

// Names a handled message's span `process payment.commands RefundRequested` instead of Spring Kafka's
// `payment.commands receive` - the span covers the listener handling the message, OpenTelemetry's `process`, not its
// receipt from the broker - and tags it with the message's type and correlation id, as they arrived.
public class KafkaListenerSpanNameConvention extends KafkaListenerObservation.DefaultKafkaListenerObservationConvention {
    private static final String PROCESS = "process";

    @Override
    public String getContextualName(KafkaRecordReceiverContext context) {
        return SpanNames.messagingSpanName(PROCESS, context.getSource(),
                SpanNames.headerValue(context.getRecord().headers(), KafkaConst.MESSAGE_TYPE));
    }

    @Override
    public KeyValues getHighCardinalityKeyValues(KafkaRecordReceiverContext context) {
        return super.getHighCardinalityKeyValues(context).and(SpanNames.messageKeyValues(context.getRecord().headers()));
    }
}
