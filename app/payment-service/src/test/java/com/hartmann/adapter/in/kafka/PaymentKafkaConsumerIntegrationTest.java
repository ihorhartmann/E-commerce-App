package com.hartmann.adapter.in.kafka;

import com.hartmann.adapter.common.kafka.KafkaConst;
import com.hartmann.adapter.out.http.exception.PaymentGatewayUnavailableException;
import com.hartmann.application.port.in.PaymentService;
import com.hartmann.application.port.in.RefundService;
import com.hartmann.application.port.out.repository.exception.ObjectNotFoundException;
import com.hartmann.domain.model.Payment;
import com.hartmann.domain.model.PaymentStatus;
import com.hartmann.domain.value.Currency;
import com.hartmann.domain.value.Money;
import com.hartmann.infrastructure.config.KafkaErrorHandlerConfig;
import com.hartmann.infrastructure.config.KafkaTopicConfig;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.header.Headers;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.kafka.autoconfigure.KafkaAutoConfiguration;
import org.springframework.boot.kafka.autoconfigure.KafkaProperties;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.test.utils.ContainerTestUtils;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.redpanda.RedpandaContainer;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@Tag("integration")
@SpringBootTest(classes = {
        PaymentKafkaConsumer.class,
        KafkaErrorHandlerConfig.class,
        KafkaTopicConfig.class
})
@ImportAutoConfiguration({KafkaAutoConfiguration.class, JacksonAutoConfiguration.class})
@EnableKafka
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_METHOD)
class PaymentKafkaConsumerIntegrationTest {
    private static final Duration MESSAGE_WAIT_TIMEOUT = Duration.ofSeconds(20);

    // Longer than MESSAGE_WAIT_TIMEOUT: a transiently failing message is only dead-lettered after
    // 5 retries, i.e. 15s of waiting, so 20s would leave too little margin.
    private static final Duration RETRY_MESSAGE_WAIT_TIMEOUT = Duration.ofSeconds(45);

    // Deliberately shorter than the 15s a retried message spends backing off before it is dead-lettered:
    // a non-retryable failure showing up in the DLT within this window is what proves it was not retried.
    private static final Duration NON_RETRYABLE_DEAD_LETTER_TIMEOUT = Duration.ofSeconds(10);

    // Bounds a single synchronous AdminClient round trip to the broker (same value as the
    // Go services' brokerCallTimeout, for the same reason).
    private static final Duration BROKER_CALL_TIMEOUT = Duration.ofSeconds(10);

    private static final int MAX_ATTEMPTS = 5;

    // Header names Spring's DeadLetterPublishingRecoverer sets on the dead-lettered record.
    private static final String DLT_ORIGINAL_TOPIC_HEADER = "kafka_dlt-original-topic";

    @Value("${spring.kafka.consumer.group-id}")
    private String groupId;

    @Container
    private static final RedpandaContainer REDPANDA = new RedpandaContainer("docker.redpanda.com/redpandadata/redpanda:v26.1.16");

    @DynamicPropertySource
    private static void kafkaProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", REDPANDA::getBootstrapServers);
    }

    @MockitoBean
    private PaymentService paymentService;
    @MockitoBean
    private RefundService refundService;

    @SuppressWarnings("SpringJavaInjectionPointsAutowiringInspection")
    @Autowired
    private KafkaListenerEndpointRegistry registry;

    @Autowired
    private KafkaProperties kafkaProperties;

    private KafkaProducer<String, String> producer;

    @BeforeEach
    void setUp() {
        registry.getListenerContainers().forEach(container -> ContainerTestUtils.waitForAssignment(container, 1));
        producer = new KafkaProducer<>(kafkaProperties.buildProducerProperties());
    }

    @AfterEach
    void tearDown() {
        producer.close();
    }

    private void send(ProducerRecord<String, String> record) {
        producer.send(record);
        producer.flush();
    }

    private ProducerRecord<String, String> commandRecord(String messageType, String correlationId, String payload) {
        var record = new ProducerRecord<>(KafkaConst.PAYMENT_COMMANDS_TOPIC, correlationId, payload);
        record.headers()
                .add(new RecordHeader(KafkaConst.MESSAGE_TYPE, messageType.getBytes(StandardCharsets.UTF_8)))
                .add(new RecordHeader(KafkaConst.CORRELATION_ID, correlationId.getBytes(StandardCharsets.UTF_8)))
                .add(new RecordHeader(KafkaConst.MESSAGE_ID, UUID.randomUUID().toString().getBytes(StandardCharsets.UTF_8)));
        return record;
    }

    // committedOffset reads the group's committed offset directly from the broker, the
    // same source of truth an independent consumer (or a restarted app instance) would
    // see - not anything cached in this test or the running listener.
    private long committedOffset() {
        try (AdminClient admin = AdminClient.create(kafkaProperties.buildAdminProperties())) {
            var partition = new TopicPartition(KafkaConst.PAYMENT_COMMANDS_TOPIC, 0);
            var offsets = admin.listConsumerGroupOffsets(groupId)
                    .partitionsToOffsetAndMetadata()
                    .get(BROKER_CALL_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
            var offsetAndMetadata = offsets.get(partition);
            return offsetAndMetadata == null ? -1L : offsetAndMetadata.offset();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // awaitDeadLetter polls the DLT with an independent consumer (its own group, reading from the
    // start) until a record with the given key shows up - the same view a redrive tool would have.
    private ConsumerRecord<String, String> awaitDeadLetter(String key, Duration timeout) {
        var properties = kafkaProperties.buildConsumerProperties();
        properties.put(ConsumerConfig.GROUP_ID_CONFIG, "dlt-reader-" + UUID.randomUUID());
        properties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");

        try (var consumer = new KafkaConsumer<String, String>(properties)) {
            consumer.assign(List.of(new TopicPartition(KafkaConst.PAYMENT_COMMANDS_DLT_TOPIC, 0)));

            var deadline = Instant.now().plus(timeout);
            while (Instant.now().isBefore(deadline)) {
                for (var record : consumer.poll(Duration.ofMillis(500))) {
                    if (key.equals(record.key())) {
                        return record;
                    }
                }
            }
        }
        throw new AssertionError("no record with key " + key + " on " + KafkaConst.PAYMENT_COMMANDS_DLT_TOPIC + " within " + timeout);
    }

    private static String headerValue(Headers headers, String name) {
        var header = headers.lastHeader(name);
        return header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
    }

    private Payment testPayment(long id, long orderId) {
        var now = Instant.now();
        return Payment.createExisting(id, orderId, "pi_test", new Money(new BigDecimal("100.00"), Currency.USD),
                PaymentStatus.CONFIRMED, List.of(), now, now, now.plusSeconds(900));
    }

    @Test
    void dispatchesARefundRequestedCommandMessageToTheRefundService() {
        var paymentId = 1L;
        var orderId = 100L;
        var amount = new Money(new BigDecimal("40.00"), Currency.USD);
        when(paymentService.getByOrderId(orderId)).thenReturn(testPayment(paymentId, orderId));

        send(commandRecord(KafkaConst.REFUND_REQUESTED_COMMAND, UUID.randomUUID().toString(),
                "{\"orderId\":" + orderId + ",\"amount\":40.00,\"currency\":\"USD\"}"));

        await().atMost(MESSAGE_WAIT_TIMEOUT)
                .untilAsserted(() -> verify(refundService, times(1)).submitRefund(any(), any(), eq(paymentId), eq(amount)));
    }

    @Test
    void ignoresUnrecognizedMessageTypesAndKeepsDispatchingSubsequentMessages() {
        var paymentId = 2L;
        var orderId = 101L;
        var amount = new Money(new BigDecimal("15.00"), Currency.USD);
        when(paymentService.getByOrderId(orderId)).thenReturn(testPayment(paymentId, orderId));

        send(commandRecord("SomeUnrelatedEvent", UUID.randomUUID().toString(), "{\"noop\":true}"));
        send(commandRecord(KafkaConst.REFUND_REQUESTED_COMMAND, UUID.randomUUID().toString(),
                "{\"orderId\":" + orderId + ",\"amount\":15.00,\"currency\":\"USD\"}"));

        await().atMost(MESSAGE_WAIT_TIMEOUT)
                .untilAsserted(() -> verify(refundService, times(1)).submitRefund(any(), any(), eq(paymentId), eq(amount)));
    }

    @Test
    void commitsTheOffsetByExactlyOneAfterSuccessfulHandling() {
        var paymentId = 3L;
        var orderId = 102L;
        when(paymentService.getByOrderId(orderId)).thenReturn(testPayment(paymentId, orderId));
        var before = committedOffset();

        send(commandRecord(KafkaConst.REFUND_REQUESTED_COMMAND, UUID.randomUUID().toString(),
                "{\"orderId\":" + orderId + ",\"amount\":5.00,\"currency\":\"USD\"}"));

        await().atMost(MESSAGE_WAIT_TIMEOUT)
                .untilAsserted(() -> assertThat(committedOffset()).isEqualTo(before + 1));
    }

    @Test
    void aTransientlyFailingMessageIsRetriedFiveTimesThenDeadLettered() {
        var orderId = 103L;
        var correlationId = UUID.randomUUID().toString();
        var record = commandRecord(KafkaConst.REFUND_REQUESTED_COMMAND, correlationId,
                "{\"orderId\":" + orderId + ",\"amount\":5.00,\"currency\":\"USD\"}");
        when(paymentService.getByOrderId(orderId)).thenThrow(new RuntimeException("simulated failure"));
        var before = committedOffset();

        send(record);

        var deadLetter = awaitDeadLetter(correlationId, RETRY_MESSAGE_WAIT_TIMEOUT);
        verify(paymentService, times(MAX_ATTEMPTS)).getByOrderId(orderId);
        verifyNoInteractions(refundService);
        assertThat(deadLetter.value()).isEqualTo(record.value());
        assertThat(headerValue(deadLetter.headers(), DLT_ORIGINAL_TOPIC_HEADER)).isEqualTo(KafkaConst.PAYMENT_COMMANDS_TOPIC);
        // The business headers survive the trip so a redrive keeps the same messageId (idempotency)
        // and correlationId (traceability).
        assertThat(headerValue(deadLetter.headers(), KafkaConst.MESSAGE_ID)).isEqualTo(headerValue(record.headers(), KafkaConst.MESSAGE_ID));
        assertThat(headerValue(deadLetter.headers(), KafkaConst.CORRELATION_ID)).isEqualTo(correlationId);
        assertThat(headerValue(deadLetter.headers(), KafkaConst.MESSAGE_TYPE)).isEqualTo(KafkaConst.REFUND_REQUESTED_COMMAND);
        await().atMost(MESSAGE_WAIT_TIMEOUT)
                .untilAsserted(() -> assertThat(committedOffset()).isEqualTo(before + 1));
    }

    @Test
    void aMalformedPayloadIsDeadLetteredWithoutBeingDispatchedOrRetried() {
        var correlationId = UUID.randomUUID().toString();
        var before = committedOffset();

        send(commandRecord(KafkaConst.REFUND_REQUESTED_COMMAND, correlationId, "this is not json"));

        var deadLetter = awaitDeadLetter(correlationId, NON_RETRYABLE_DEAD_LETTER_TIMEOUT);
        assertThat(deadLetter.value()).isEqualTo("this is not json");
        verifyNoInteractions(paymentService, refundService);
        await().atMost(MESSAGE_WAIT_TIMEOUT)
                .untilAsserted(() -> assertThat(committedOffset()).isEqualTo(before + 1));
    }

    @Test
    void aNonRetryableApplicationFailureIsDeadLetteredAfterASingleAttempt() {
        var orderId = 104L;
        var correlationId = UUID.randomUUID().toString();
        when(paymentService.getByOrderId(orderId)).thenThrow(new ObjectNotFoundException("no payment for order " + orderId));

        send(commandRecord(KafkaConst.REFUND_REQUESTED_COMMAND, correlationId,
                "{\"orderId\":" + orderId + ",\"amount\":5.00,\"currency\":\"USD\"}"));

        awaitDeadLetter(correlationId, NON_RETRYABLE_DEAD_LETTER_TIMEOUT);
        verify(paymentService, times(1)).getByOrderId(orderId);
        verifyNoInteractions(refundService);
    }

    // PaymentGatewayUnavailableException is an ApplicationException like the non-retryable
    // ObjectNotFoundException above, but is deliberately absent from the not-retryable list.
    @Test
    void aRetryableApplicationFailureIsRetriedFiveTimesThenDeadLettered() {
        var paymentId = 5L;
        var orderId = 105L;
        var amountValue = "5.00";
        var amount = new Money(new BigDecimal(amountValue), Currency.USD);
        var correlationId = UUID.randomUUID().toString();
        when(paymentService.getByOrderId(orderId)).thenReturn(testPayment(paymentId, orderId));
        when(refundService.submitRefund(any(), any(), eq(paymentId), eq(amount)))
                .thenThrow(new PaymentGatewayUnavailableException("gateway unavailable", new RuntimeException("stripe is down")));

        send(commandRecord(KafkaConst.REFUND_REQUESTED_COMMAND, correlationId,
                "{\"orderId\":" + orderId + ",\"amount\":" + amountValue + ",\"currency\":\"USD\"}"));

        awaitDeadLetter(correlationId, RETRY_MESSAGE_WAIT_TIMEOUT);
        verify(paymentService, times(MAX_ATTEMPTS)).getByOrderId(orderId);
        verify(refundService, times(MAX_ATTEMPTS)).submitRefund(any(), any(), eq(paymentId), eq(amount));
    }

    @Test
    void aMessageWhoseMessageIdIsNotAUuidIsDeadLetteredWithoutBeingDispatchedOrRetried() {
        var correlationId = UUID.randomUUID().toString();
        var record = commandRecord(KafkaConst.REFUND_REQUESTED_COMMAND, correlationId,
                "{\"orderId\":106,\"amount\":5.00,\"currency\":\"USD\"}");
        record.headers()
                .remove(KafkaConst.MESSAGE_ID)
                .add(new RecordHeader(KafkaConst.MESSAGE_ID, "not-a-uuid".getBytes(StandardCharsets.UTF_8)));

        send(record);

        awaitDeadLetter(correlationId, NON_RETRYABLE_DEAD_LETTER_TIMEOUT);
        verifyNoInteractions(paymentService, refundService);
    }

    @Test
    void aMessageMissingARequiredHeaderIsDeadLetteredWithoutBeingDispatchedToAnyService() {
        var key = "no-headers";
        var record = new ProducerRecord<>(KafkaConst.PAYMENT_COMMANDS_TOPIC, key, "{}");
        record.headers().add(new RecordHeader(KafkaConst.MESSAGE_TYPE, KafkaConst.REFUND_REQUESTED_COMMAND.getBytes(StandardCharsets.UTF_8)));
        // correlationId header intentionally omitted - @Header binding should fail
        // before the listener body (and therefore either service) is ever reached.
        send(record);

        awaitDeadLetter(key, NON_RETRYABLE_DEAD_LETTER_TIMEOUT);

        verifyNoInteractions(paymentService, refundService);
    }
}
