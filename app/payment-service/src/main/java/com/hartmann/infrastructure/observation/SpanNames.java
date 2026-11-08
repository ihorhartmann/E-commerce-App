package com.hartmann.infrastructure.observation;

import com.hartmann.adapter.common.kafka.KafkaConst;
import com.hartmann.adapter.common.tracing.CorrelationConst;
import io.micrometer.common.KeyValue;
import io.micrometer.common.KeyValues;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.Headers;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;

// Span names and attributes in one format across all services, the ones OpenTelemetry's semantic conventions give.
public final class SpanNames {
    // The saga's correlation id - the conventions' name for what they also call a correlation id.
    public static final String CONVERSATION_ID = CorrelationConst.CONVERSATION_ID_KEY;
    public static final String MESSAGE_TYPE = "messaging.message.type";
    public static final String MESSAGE_ID = "messaging.message.id";

    // OpenTelemetry's name for a request whose method isn't known.
    static final String UNKNOWN_METHOD = "HTTP";

    private SpanNames() {
    }

    // `{METHOD} {route}`, OpenTelemetry's HTTP span name: the method in upper case, the route as a template
    // (`/api/v1/payments/{orderId}`, never the path of one request); the method alone when no template is known.
    public static String httpSpanName(String method, String route) {
        return route == null || route.isEmpty() ? method : method + " " + route;
    }

    // `{operation} {topic} {messageType}`: OpenTelemetry's messaging span name with the message type the headers carry
    // added at the end - as it arrived, even an unknown one; without the header, just `{operation} {topic}`.
    public static String messagingSpanName(String operation, String topic, String messageType) {
        return messageType == null || messageType.isEmpty() ? operation + " " + topic : operation + " " + topic + " " + messageType;
    }

    // The value of the last header named key, as it arrived; null when there is none.
    public static String headerValue(Headers headers, String key) {
        Header header = headers.lastHeader(key);
        return header == null || header.value() == null ? null : new String(header.value(), StandardCharsets.UTF_8);
    }

    // What a message's headers say about it, as span attributes: its type, id and the saga's correlation id - as they
    // arrived, even invalid ones, so a bad message can be traced to wherever it came from. A missing header adds nothing.
    public static KeyValues messageKeyValues(Headers headers) {
        var keyValues = new ArrayList<KeyValue>();
        var messageType = headerValue(headers, KafkaConst.MESSAGE_TYPE);
        if (messageType != null) {
            keyValues.add(KeyValue.of(MESSAGE_TYPE, messageType));
        }
        var messageId = headerValue(headers, KafkaConst.MESSAGE_ID);
        if (messageId != null) {
            keyValues.add(KeyValue.of(MESSAGE_ID, messageId));
        }
        var correlationId = headerValue(headers, KafkaConst.CORRELATION_ID);
        if (correlationId != null) {
            keyValues.add(KeyValue.of(CONVERSATION_ID, correlationId));
        }
        return KeyValues.of(keyValues);
    }
}
