package com.hartmann.frameworks.observation

import com.hartmann.interfaceadapter.common.tracing.KafkaTracingAttributes
import com.hartmann.usecase.port.common.kafka.KafkaConst
import io.micrometer.common.KeyValue
import io.micrometer.common.KeyValues
import org.apache.kafka.common.header.Headers
import java.nio.charset.StandardCharsets

/** A URI template's path - the scheme and host of a client's base URL dropped, as Spring's own conventions do. */
private val SCHEME_AND_HOST = Regex("^https?://[^/]+")

/** OpenTelemetry's name for a request whose method isn't known. */
const val UNKNOWN_METHOD = "HTTP"

/**
 * `{METHOD} {route}`, OpenTelemetry's HTTP span name: the method in upper case, the route as a template
 * (`/api/v1/orders/{orderId}`, never the path of one request); the method alone when no template is known.
 */
fun httpSpanName(method: String, route: String?): String {
    val path = route?.replace(SCHEME_AND_HOST, "")
    return if (path.isNullOrEmpty()) method else "$method $path"
}

/**
 * `{operation} {topic} {messageType}`: OpenTelemetry's messaging span name with the message type the headers carry
 * added at the end - as it arrived, even an unknown one; without the header, just `{operation} {topic}`.
 */
fun messagingSpanName(operation: String, topic: String, messageType: String?): String =
    if (messageType.isNullOrEmpty()) "$operation $topic" else "$operation $topic $messageType"

/** The value of the last header named [key], as it arrived; null when there is none. */
fun headerValue(headers: Headers, key: String): String? =
    headers.lastHeader(key)?.value()?.toString(StandardCharsets.UTF_8)

/**
 * What a message's headers say about it, as span attributes: its type, id and the saga's correlation id - as they
 * arrived, even invalid ones, so a bad message can be traced to wherever it came from. A missing header adds nothing.
 */
fun messageKeyValues(headers: Headers): KeyValues {
    val keyValues = listOfNotNull(
        headerValue(headers, KafkaConst.MESSAGE_TYPE)?.let { KeyValue.of(KafkaTracingAttributes.MESSAGE_TYPE, it) },
        headerValue(headers, KafkaConst.MESSAGE_ID)?.let { KeyValue.of(KafkaTracingAttributes.MESSAGE_ID, it) },
        headerValue(headers, KafkaConst.CORRELATION_ID)?.let { KeyValue.of(KafkaTracingAttributes.CONVERSATION_ID, it) },
    )
    return KeyValues.of(keyValues)
}
