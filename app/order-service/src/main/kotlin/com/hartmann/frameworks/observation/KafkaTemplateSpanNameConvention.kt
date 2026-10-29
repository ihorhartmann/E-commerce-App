package com.hartmann.frameworks.observation

import com.hartmann.usecase.port.common.kafka.KafkaConst
import io.micrometer.common.KeyValues
import org.springframework.kafka.support.micrometer.KafkaRecordSenderContext
import org.springframework.kafka.support.micrometer.KafkaTemplateObservation

/**
 * Names a sent message's span `send payment.commands RefundRequested` instead of Spring Kafka's
 * `payment.commands send`, and tags it with the message's type and correlation id.
 */
class KafkaTemplateSpanNameConvention : KafkaTemplateObservation.DefaultKafkaTemplateObservationConvention() {
    override fun getContextualName(context: KafkaRecordSenderContext): String =
        messagingSpanName("send", context.destination, headerValue(context.record.headers(), KafkaConst.MESSAGE_TYPE))

    override fun getHighCardinalityKeyValues(context: KafkaRecordSenderContext): KeyValues =
        super.getHighCardinalityKeyValues(context).and(messageKeyValues(context.record.headers()))
}
