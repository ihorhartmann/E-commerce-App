package com.hartmann.infrastructure.observation;

import com.hartmann.adapter.common.kafka.KafkaConst;
import io.micrometer.common.KeyValue;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@TestInstance(TestInstance.Lifecycle.PER_METHOD)
class SpanNamesTest {

    @Test
    void httpSpanName_joinsTheMethodAndTheRouteTemplate() {
        assertEquals("GET /api/v1/payments/{orderId}", SpanNames.httpSpanName("GET", "/api/v1/payments/{orderId}"));
    }

    @Test
    void httpSpanName_isTheMethodAloneWhenNoRouteIsKnown() {
        assertEquals("POST", SpanNames.httpSpanName("POST", null));
    }

    @Test
    void messagingSpanName_joinsTheOperationTheTopicAndTheMessageType() {
        assertEquals("send payment.events PaymentCompleted", SpanNames.messagingSpanName("send", "payment.events", "PaymentCompleted"));
    }

    @Test
    void messagingSpanName_isTheOperationAndTheTopicAloneWithoutAMessageType() {
        assertEquals("process payment.commands", SpanNames.messagingSpanName("process", "payment.commands", null));
    }

    @Test
    void headerValue_isTheValueOfTheLastHeaderWithTheKey() {
        var key = KafkaConst.MESSAGE_TYPE;
        var headers = new RecordHeaders()
                .add(key, "First".getBytes(StandardCharsets.UTF_8))
                .add(key, "Last".getBytes(StandardCharsets.UTF_8));

        assertEquals("Last", SpanNames.headerValue(headers, key));
    }

    @Test
    void headerValue_isNullWithoutAHeaderWithTheKey() {
        assertNull(SpanNames.headerValue(new RecordHeaders(), KafkaConst.MESSAGE_TYPE));
    }

    @Test
    void messageKeyValues_tagsTheTypeAndCorrelationIdAsTheyArrived() {
        var messageType = "SomethingUnknown";
        var messageId = "not-a-uuid";
        var correlationId = "not-a-real-id";
        var headers = new RecordHeaders()
                .add(KafkaConst.MESSAGE_TYPE, messageType.getBytes(StandardCharsets.UTF_8))
                .add(KafkaConst.MESSAGE_ID, messageId.getBytes(StandardCharsets.UTF_8))
                .add(KafkaConst.CORRELATION_ID, correlationId.getBytes(StandardCharsets.UTF_8));

        var actual = SpanNames.messageKeyValues(headers).stream().toList();

        assertTrue(actual.contains(KeyValue.of(SpanNames.MESSAGE_TYPE, messageType)));
        assertTrue(actual.contains(KeyValue.of(SpanNames.MESSAGE_ID, messageId)));
        assertTrue(actual.contains(KeyValue.of(SpanNames.CONVERSATION_ID, correlationId)));
    }

    @Test
    void messageKeyValues_addsNothingForAMissingHeader() {
        assertTrue(SpanNames.messageKeyValues(new RecordHeaders()).stream().toList().isEmpty());
    }
}
