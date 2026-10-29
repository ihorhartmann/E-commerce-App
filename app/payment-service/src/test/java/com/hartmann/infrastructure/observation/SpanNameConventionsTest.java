package com.hartmann.infrastructure.observation;

import com.hartmann.adapter.common.kafka.KafkaConst;
import io.micrometer.common.KeyValue;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.http.server.observation.ServerRequestObservationContext;
import org.springframework.kafka.support.micrometer.KafkaRecordReceiverContext;
import org.springframework.kafka.support.micrometer.KafkaRecordSenderContext;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@TestInstance(TestInstance.Lifecycle.PER_METHOD)
class SpanNameConventionsTest {

    @Test
    void getContextualName_namesAnIncomingRequestByItsMethodAndRouteTemplate() {
        var context = new ServerRequestObservationContext(new MockHttpServletRequest("GET", "/api/v1/payments/42"), new MockHttpServletResponse());
        context.setPathPattern("/api/v1/payments/{orderId}");

        assertEquals("GET /api/v1/payments/{orderId}", new HttpServerSpanNameConvention().getContextualName(context));
    }

    @Test
    void getContextualName_namesASentMessageSendItsTopicAndItsType() {
        var topic = KafkaConst.PAYMENT_EVENTS_TOPIC;
        var headers = new RecordHeaders().add(KafkaConst.MESSAGE_TYPE, KafkaConst.PAYMENT_COMPLETED_EVENT.getBytes(StandardCharsets.UTF_8));
        var context = new KafkaRecordSenderContext(new ProducerRecord<>(topic, null, "1", "{}", headers), "kafkaTemplate", () -> "cluster");

        assertEquals("send " + topic + " " + KafkaConst.PAYMENT_COMPLETED_EVENT,
                new KafkaTemplateSpanNameConvention().getContextualName(context));
    }

    @Test
    void getHighCardinalityKeyValues_tagsASentMessageWithItsCorrelationId() {
        var correlationId = "42";
        var headers = new RecordHeaders().add(KafkaConst.CORRELATION_ID, correlationId.getBytes(StandardCharsets.UTF_8));
        var context = new KafkaRecordSenderContext(
                new ProducerRecord<>(KafkaConst.PAYMENT_EVENTS_TOPIC, null, "1", "{}", headers), "kafkaTemplate", () -> "cluster");

        var actual = new KafkaTemplateSpanNameConvention().getHighCardinalityKeyValues(context).stream().toList();

        assertTrue(actual.contains(KeyValue.of(SpanNames.CONVERSATION_ID, correlationId)));
    }

    @Test
    void getContextualName_namesAHandledMessageProcessItsTopicAndItsTypeAsItArrived() {
        var topic = "payment.commands";
        var messageType = "SomethingUnknown";
        var record = new ConsumerRecord<>(topic, 0, 0L, "1", "{}");
        record.headers().add(KafkaConst.MESSAGE_TYPE, messageType.getBytes(StandardCharsets.UTF_8));
        var context = new KafkaRecordReceiverContext(record, "listener", () -> "cluster");

        assertEquals("process " + topic + " " + messageType, new KafkaListenerSpanNameConvention().getContextualName(context));
    }

    @Test
    void getContextualName_namesAHandledMessageWithoutATypeHeaderProcessAndItsTopicAlone() {
        var topic = "payment.commands";
        var context = new KafkaRecordReceiverContext(new ConsumerRecord<>(topic, 0, 0L, "1", "{}"), "listener", () -> "cluster");

        assertEquals("process " + topic, new KafkaListenerSpanNameConvention().getContextualName(context));
    }

    @Test
    void getHighCardinalityKeyValues_tagsAHandledMessageWithItsCorrelationId() {
        var correlationId = "43";
        var record = new ConsumerRecord<>("payment.commands", 0, 0L, "1", "{}");
        record.headers().add(KafkaConst.CORRELATION_ID, correlationId.getBytes(StandardCharsets.UTF_8));
        var context = new KafkaRecordReceiverContext(record, "listener", () -> "cluster");

        var actual = new KafkaListenerSpanNameConvention().getHighCardinalityKeyValues(context).stream().toList();

        assertTrue(actual.contains(KeyValue.of(SpanNames.CONVERSATION_ID, correlationId)));
    }
}
