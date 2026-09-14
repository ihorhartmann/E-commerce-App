package com.hartmann.interfaceadapter.`in`.kafka

import com.hartmann.frameworks.config.KafkaErrorHandlerConfig
import com.hartmann.usecase.port.`in`.saga.FulfillOrderSaga
import com.hartmann.usecase.port.common.kafka.KafkaConst
import org.apache.kafka.clients.admin.AdminClient
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.header.internals.RecordHeader
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.atLeast
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.ImportAutoConfiguration
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration
import org.springframework.boot.kafka.autoconfigure.KafkaAutoConfiguration
import org.springframework.boot.kafka.autoconfigure.KafkaProperties
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.kafka.annotation.EnableKafka
import org.springframework.kafka.config.KafkaListenerEndpointRegistry
import org.springframework.kafka.test.utils.ContainerTestUtils
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.redpanda.RedpandaContainer
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.UUID
import java.util.concurrent.TimeUnit

@Tag("integration")
@SpringBootTest(classes = [OrderKafkaConsumer::class, KafkaErrorHandlerConfig::class])
@ImportAutoConfiguration(KafkaAutoConfiguration::class, JacksonAutoConfiguration::class)
@EnableKafka
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_METHOD)
class OrderKafkaConsumerIntegrationTest {

    companion object {
        private val MESSAGE_WAIT_TIMEOUT: Duration = Duration.ofSeconds(20)

        // Longer than MESSAGE_WAIT_TIMEOUT: the offset only advances after 1 initial attempt + 3
        // retries, 1s apart (KafkaErrorHandlerConfig's FixedBackOff(1000, 3)), so 20s leaves
        // too little margin.
        private val RETRY_MESSAGE_WAIT_TIMEOUT: Duration = Duration.ofSeconds(30)

        // Bounds a single synchronous AdminClient round trip to the broker (same value as
        // the Go services' brokerCallTimeout, for the same reason).
        private val BROKER_CALL_TIMEOUT: Duration = Duration.ofSeconds(10)

        // How long to wait before asserting a header-invalid message was never dispatched -
        // there's no follow-up message here to detect processing via (unlike the offset-based
        // tests), so this is a fixed settle time rather than a polled condition. Same value as
        // the Go services' noExtraMessageWait, for the same "confirm nothing extra happened" intent.
        private val NO_DISPATCH_WAIT: Duration = Duration.ofSeconds(5)

        @Container
        @JvmStatic
        private val REDPANDA = RedpandaContainer("docker.redpanda.com/redpandadata/redpanda:v26.1.16")

        @DynamicPropertySource
        @JvmStatic
        fun kafkaProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.kafka.bootstrap-servers") { REDPANDA.bootstrapServers }
        }
    }

    @Value($$"${spring.kafka.consumer.group-id}")
    private lateinit var groupId: String

    @MockitoBean
    private lateinit var saga: FulfillOrderSaga

    @Suppress("SpringJavaInjectionPointsAutowiringInspection")
    @Autowired
    private lateinit var registry: KafkaListenerEndpointRegistry

    @Autowired
    private lateinit var kafkaProperties: KafkaProperties

    private lateinit var producer: KafkaProducer<String, String>

    @BeforeEach
    fun setUp() {
        registry.listenerContainers.forEach { ContainerTestUtils.waitForAssignment(it, 1) }
        producer = KafkaProducer(kafkaProperties.buildProducerProperties())
    }

    @AfterEach
    fun tearDown() {
        producer.close()
    }

    private fun send(record: ProducerRecord<String, String>) {
        producer.send(record)
        producer.flush()
    }

    private fun eventRecord(topic: String, messageType: String, correlationId: String, payload: String): ProducerRecord<String, String> {
        val record = ProducerRecord(topic, correlationId, payload)
        record.headers()
            .add(RecordHeader(KafkaConst.MESSAGE_TYPE, messageType.toByteArray(StandardCharsets.UTF_8)))
            .add(RecordHeader(KafkaConst.CORRELATION_ID, correlationId.toByteArray(StandardCharsets.UTF_8)))
            .add(RecordHeader(KafkaConst.MESSAGE_ID, UUID.randomUUID().toString().toByteArray(StandardCharsets.UTF_8)))
        return record
    }

    // committedOffset reads the group's committed offset directly from the broker, the
    // same source of truth an independent consumer (or a restarted app instance) would
    // see - not anything cached in this test or the running listener.
    private fun committedOffset(): Long {
        AdminClient.create(kafkaProperties.buildAdminProperties()).use { admin ->
            val partition = TopicPartition(KafkaConst.PAYMENT_EVENTS_TOPIC, 0)
            val offsets = admin.listConsumerGroupOffsets(groupId).partitionsToOffsetAndMetadata()
                .get(BROKER_CALL_TIMEOUT.toSeconds(), TimeUnit.SECONDS)
            return offsets[partition]?.offset() ?: -1L
        }
    }

    @Test
    fun `onPaymentEvent - dispatches a PaymentCompleted event to the saga`() {
        val orderId = 100L
        val correlationId = UUID.randomUUID().toString()

        send(eventRecord(KafkaConst.PAYMENT_EVENTS_TOPIC, KafkaConst.PAYMENT_COMPLETED_EVENT, correlationId, "{\"orderId\":$orderId}"))

        await().atMost(MESSAGE_WAIT_TIMEOUT)
            .untilAsserted { verify(saga, times(1)).onPaymentCompleted(any(), eq(orderId)) }
    }

    @Test
    fun `onShippingEvent - dispatches a ShipmentCreated event to the saga`() {
        val orderId = 101L
        val trackingNumber = "TRACK-1"
        val correlationId = UUID.randomUUID().toString()

        send(
            eventRecord(
                KafkaConst.SHIPPING_EVENTS_TOPIC, KafkaConst.SHIPMENT_CREATED_EVENT, correlationId,
                "{\"orderId\":$orderId,\"trackingNumber\":\"$trackingNumber\"}",
            )
        )

        await().atMost(MESSAGE_WAIT_TIMEOUT)
            .untilAsserted { verify(saga, times(1)).onShipmentCreated(any(), eq(orderId), eq(trackingNumber)) }
    }

    @Test
    fun `ignores unrecognized message types and keeps dispatching subsequent messages`() {
        val orderId = 102L
        val correlationId = UUID.randomUUID().toString()

        send(eventRecord(KafkaConst.PAYMENT_EVENTS_TOPIC, "SomeUnrelatedEvent", UUID.randomUUID().toString(), "{\"noop\":true}"))
        send(eventRecord(KafkaConst.PAYMENT_EVENTS_TOPIC, KafkaConst.PAYMENT_COMPLETED_EVENT, correlationId, "{\"orderId\":$orderId}"))

        await().atMost(MESSAGE_WAIT_TIMEOUT)
            .untilAsserted { verify(saga, times(1)).onPaymentCompleted(any(), eq(orderId)) }
    }

    @Test
    fun `commits the offset by exactly one after successful handling`() {
        val orderId = 103L
        val correlationId = UUID.randomUUID().toString()
        val before = committedOffset()

        send(eventRecord(KafkaConst.PAYMENT_EVENTS_TOPIC, KafkaConst.PAYMENT_COMPLETED_EVENT, correlationId, "{\"orderId\":$orderId}"))

        await().atMost(MESSAGE_WAIT_TIMEOUT)
            .untilAsserted { assertEquals(before + 1, committedOffset()) }
    }

    // Now that the listener container factory comes from Spring Boot's own autoconfiguration,
    // it auto-detects KafkaErrorHandlerConfig's DefaultErrorHandler bean (FixedBackOff(1000, 3))
    // and actually applies it: 1 initial attempt + 3 retries, 1s apart, then the offset advances
    // past the poison message. This test still asserts the general shape (advances eventually,
    // doesn't retry forever) rather than hardcoding the exact attempt count, so it stays
    // meaningful even if that backoff's numbers are deliberately changed later.
    @Test
    fun `a message that fails handling is retried and the offset eventually advances`() {
        val orderId = 104L
        val correlationId = UUID.randomUUID().toString()
        whenever(saga.onPaymentCompleted(any(), any())).thenThrow(RuntimeException("simulated failure"))
        val before = committedOffset()

        send(eventRecord(KafkaConst.PAYMENT_EVENTS_TOPIC, KafkaConst.PAYMENT_COMPLETED_EVENT, correlationId, "{\"orderId\":$orderId}"))

        await().atMost(RETRY_MESSAGE_WAIT_TIMEOUT)
            .untilAsserted { assertEquals(before + 1, committedOffset()) }

        verify(saga, atLeast(1)).onPaymentCompleted(any(), eq(orderId))
    }

    @Test
    fun `a message missing a required header is not dispatched to the saga`() {
        val record = ProducerRecord(KafkaConst.PAYMENT_EVENTS_TOPIC, "no-headers", "{}")
        record.headers().add(RecordHeader(KafkaConst.MESSAGE_TYPE, KafkaConst.PAYMENT_COMPLETED_EVENT.toByteArray(StandardCharsets.UTF_8)))
        // correlationId header intentionally omitted - @Header binding should fail before the
        // listener body (and therefore the saga) is ever reached.
        send(record)

        TimeUnit.SECONDS.sleep(NO_DISPATCH_WAIT.toSeconds())

        verifyNoInteractions(saga)
    }
}
