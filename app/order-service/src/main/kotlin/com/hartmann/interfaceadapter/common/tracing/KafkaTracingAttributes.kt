package com.hartmann.interfaceadapter.common.tracing

/** Span attribute names, as OpenTelemetry's semantic conventions name them, shared by every span this service tags. */
object KafkaTracingAttributes {
    /** The saga's correlation id - the conventions' name for what they also call a correlation id. */
    const val CONVERSATION_ID = "messaging.message.conversation_id"

    const val MESSAGE_TYPE = "messaging.message.type"

    const val MESSAGE_ID = "messaging.message.id"
}
