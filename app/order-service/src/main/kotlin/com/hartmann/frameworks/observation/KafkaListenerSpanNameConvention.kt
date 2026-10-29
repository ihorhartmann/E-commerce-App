package com.hartmann.frameworks.observation

import com.hartmann.usecase.port.common.kafka.KafkaConst
import io.micrometer.common.KeyValues
import org.springframework.kafka.support.micrometer.KafkaListenerObservation
import org.springframework.kafka.support.micrometer.KafkaRecordReceiverContext

/**
 * Names a handled message's span `process payment.events PaymentCompleted` instead of Spring Kafka's
 * `payment.events receive` - the span covers the listener handling the message, OpenTelemetry's `process`, not its
 * receipt from the broker - and tags it with the message's type and correlation id, as they arrived.
 */
class KafkaListenerSpanNameConvention : KafkaListenerObservation.DefaultKafkaListenerObservationConvention() {
    override fun getContextualName(context: KafkaRecordReceiverContext): String =
        messagingSpanName("process", context.source, headerValue(context.record.headers(), KafkaConst.MESSAGE_TYPE))

    override fun getHighCardinalityKeyValues(context: KafkaRecordReceiverContext): KeyValues =
        super.getHighCardinalityKeyValues(context).and(messageKeyValues(context.record.headers()))
}
