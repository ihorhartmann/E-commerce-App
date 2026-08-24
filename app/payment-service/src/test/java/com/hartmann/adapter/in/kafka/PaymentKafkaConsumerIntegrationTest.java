package com.hartmann.adapter.in.kafka;

import com.hartmann.adapter.common.kafka.KafkaConst;
import com.hartmann.application.port.in.PaymentService;
import com.hartmann.application.port.in.RefundService;
import com.hartmann.domain.model.Payment;
import com.hartmann.domain.model.PaymentStatus;
import com.hartmann.domain.value.Currency;
import com.hartmann.domain.value.Money;
import com.hartmann.infrastructure.config.KafkaErrorHandlerConfig;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
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
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@Tag("integration")
@SpringBootTest(classes = {
        PaymentKafkaConsumer.class,
        KafkaErrorHandlerConfig.class
})
@ImportAutoConfiguration({KafkaAutoConfiguration.class, JacksonAutoConfiguration.class})
@EnableKafka
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_METHOD)
class PaymentKafkaConsumerIntegrationTest {
    private static final Duration MESSAGE_WAIT_TIMEOUT = Duration.ofSeconds(20);

    // Longer than MESSAGE_WAIT_TIMEOUT: the offset only advances after 1 initial attempt + 3
    // retries, 1s apart (KafkaErrorHandlerConfig's FixedBackOff(1000, 3)), so 20s leaves
    // too little margin.
    private static final Duration RETRY_MESSAGE_WAIT_TIMEOUT = Duration.ofSeconds(30);

    // Bounds a single synchronous AdminClient round trip to the broker (same value as the
    // Go services' brokerCallTimeout, for the same reason).
    private static final Duration BROKER_CALL_TIMEOUT = Duration.ofSeconds(10);

    // How long to wait before asserting a header-invalid message was never dispatched -
    // there's no follow-up message here to detect processing via (unlike the offset-based
    // tests), so this is a fixed settle time rather than a polled condition. Same value as
    // the Go services' noExtraMessageWait, for the same "confirm nothing extra happened" intent.
    private static final Duration NO_DISPATCH_WAIT = Duration.ofSeconds(5);

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

    private Payment testPayment(long id, long orderId) {
        var now = Instant.now();
        return Payment.createExisting(id, orderId, "pi_test", new Money(new BigDecimal("100.00"), Currency.USD),
                PaymentStatus.COMPLETED, List.of(), now, now);
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
                .untilAsserted(() -> verify(refundService, times(1)).submitRefund(paymentId, amount));
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
                .untilAsserted(() -> verify(refundService, times(1)).submitRefund(paymentId, amount));
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
    void aMessageThatFailsHandlingIsRetriedAndTheOffsetEventuallyAdvances() {
        var orderId = 103L;
        when(paymentService.getByOrderId(orderId)).thenThrow(new RuntimeException("simulated failure"));
        var before = committedOffset();

        send(commandRecord(KafkaConst.REFUND_REQUESTED_COMMAND, UUID.randomUUID().toString(),
                "{\"orderId\":" + orderId + ",\"amount\":5.00,\"currency\":\"USD\"}"));

        await().atMost(RETRY_MESSAGE_WAIT_TIMEOUT)
                .untilAsserted(() -> assertThat(committedOffset()).isEqualTo(before + 1));

        verify(paymentService, atLeast(1)).getByOrderId(orderId);
    }

    @Test
    void aMessageMissingARequiredHeaderIsNotDispatchedToAnyService() throws InterruptedException {
        var record = new ProducerRecord<>(KafkaConst.PAYMENT_COMMANDS_TOPIC, "no-headers", "{}");
        record.headers().add(new RecordHeader(KafkaConst.MESSAGE_TYPE, KafkaConst.REFUND_REQUESTED_COMMAND.getBytes(StandardCharsets.UTF_8)));
        // correlationId header intentionally omitted - @Header binding should fail
        // before the listener body (and therefore either service) is ever reached.
        send(record);

        TimeUnit.SECONDS.sleep(NO_DISPATCH_WAIT.toSeconds());

        verifyNoInteractions(paymentService, refundService);
    }
}
