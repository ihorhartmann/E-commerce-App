package com.hartmann.interfaceadapter.`in`.kafka

import com.hartmann.entities.model.OrderStatus
import com.hartmann.entities.model.exception.OrderTransitionNotAllowedException
import com.hartmann.frameworks.config.KafkaErrorHandlerConfig
import com.hartmann.frameworks.config.KafkaTopicConfig
import com.hartmann.usecase.port.`in`.saga.FulfillOrderSaga
import com.hartmann.usecase.port.common.kafka.KafkaConst
import com.hartmann.usecase.port.out.repository.exception.OrderNotFoundException
import org.apache.kafka.clients.admin.AdminClient
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.header.Headers
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
import org.springframework.dao.QueryTimeoutException
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
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit

@Tag("integration")
@SpringBootTest(classes = [OrderKafkaConsumer::class, KafkaErrorHandlerConfig::class, KafkaTopicConfig::class])
@ImportAutoConfiguration(KafkaAutoConfiguration::class, JacksonAutoConfiguration::class)
@EnableKafka
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_METHOD)
class OrderKafkaConsumerIntegrationTest {

    companion object {
        private val MESSAGE_WAIT_TIMEOUT: Duration = Duration.ofSeconds(20)

        // Longer than MESSAGE_WAIT_TIMEOUT: a transiently failing message is only dead-lettered after
        // 5 retries, i.e. 15s of waiting, so 20s would leave too little margin.
        private val RETRY_MESSAGE_WAIT_TIMEOUT: Duration = Duration.ofSeconds(45)

        // Deliberately shorter than the 15s a retried message spends backing off before it is dead-lettered:
        // a non-retryable failure showing up in the DLT within this window is what proves it was not retried.
        private val NON_RETRYABLE_DEAD_LETTER_TIMEOUT: Duration = Duration.ofSeconds(10)

        // Bounds a single synchronous AdminClient round trip to the broker (same value as
        // the Go services' brokerCallTimeout, for the same reason).
        private val BROKER_CALL_TIMEOUT: Duration = Duration.ofSeconds(10)

        private const val MAX_ATTEMPTS = 5

        // Header name Spring's DeadLetterPublishingRecoverer sets on the dead-lettered record.
        private const val DLT_ORIGINAL_TOPIC_HEADER = "kafka_dlt-original-topic"

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

    // awaitDeadLetter polls the DLT with an independent consumer (its own group, reading from the
    // start) until a record with the given key shows up - the same view a redrive tool would have.
    private fun awaitDeadLetter(dltTopic: String, key: String, timeout: Duration): ConsumerRecord<String, String> {
        val properties = kafkaProperties.buildConsumerProperties()
        properties[ConsumerConfig.GROUP_ID_CONFIG] = "dlt-reader-${UUID.randomUUID()}"
        properties[ConsumerConfig.AUTO_OFFSET_RESET_CONFIG] = "earliest"

        KafkaConsumer<String, String>(properties).use { consumer ->
            consumer.assign(listOf(TopicPartition(dltTopic, 0)))

            val deadline = Instant.now().plus(timeout)
            while (Instant.now().isBefore(deadline)) {
                consumer.poll(Duration.ofMillis(500)).firstOrNull { it.key() == key }?.let { return it }
            }
        }
        throw AssertionError("no record with key $key on $dltTopic within $timeout")
    }

    private fun headerValue(headers: Headers, name: String): String? =
        headers.lastHeader(name)?.value()?.toString(StandardCharsets.UTF_8)

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
        val shipmentId = 5L
        val correlationId = UUID.randomUUID().toString()

        send(
            eventRecord(
                KafkaConst.SHIPPING_EVENTS_TOPIC, KafkaConst.SHIPMENT_CREATED_EVENT, correlationId,
                "{\"orderId\":$orderId,\"shipmentId\":$shipmentId}",
            )
        )

        await().atMost(MESSAGE_WAIT_TIMEOUT)
            .untilAsserted { verify(saga, times(1)).onShipmentCreated(any(), eq(orderId), eq(shipmentId)) }
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

    @Test
    fun `onPaymentEvent - a transiently failing message is retried five times then dead-lettered`() {
        val orderId = 104L
        val correlationId = UUID.randomUUID().toString()
        val record = eventRecord(KafkaConst.PAYMENT_EVENTS_TOPIC, KafkaConst.PAYMENT_COMPLETED_EVENT, correlationId, "{\"orderId\":$orderId}")
        whenever(saga.onPaymentCompleted(any(), eq(orderId))).thenThrow(RuntimeException("simulated failure"))
        val before = committedOffset()

        send(record)

        val deadLetter = awaitDeadLetter(KafkaConst.PAYMENT_EVENTS_DLT_TOPIC, correlationId, RETRY_MESSAGE_WAIT_TIMEOUT)
        verify(saga, times(MAX_ATTEMPTS)).onPaymentCompleted(any(), eq(orderId))
        assertEquals(record.value(), deadLetter.value())
        assertEquals(KafkaConst.PAYMENT_EVENTS_TOPIC, headerValue(deadLetter.headers(), DLT_ORIGINAL_TOPIC_HEADER))
        // The business headers survive the trip so a redrive keeps the same messageId (idempotency)
        // and correlationId (traceability).
        assertEquals(headerValue(record.headers(), KafkaConst.MESSAGE_ID), headerValue(deadLetter.headers(), KafkaConst.MESSAGE_ID))
        assertEquals(correlationId, headerValue(deadLetter.headers(), KafkaConst.CORRELATION_ID))
        assertEquals(KafkaConst.PAYMENT_COMPLETED_EVENT, headerValue(deadLetter.headers(), KafkaConst.MESSAGE_TYPE))
        await().atMost(MESSAGE_WAIT_TIMEOUT)
            .untilAsserted { assertEquals(before + 1, committedOffset()) }
    }

    // QueryTimeoutException is what a database timeout looks like here: an infrastructure failure
    // that is no OrderServiceException at all, so nothing says it is permanent.
    @Test
    fun `onPaymentEvent - a retryable infrastructure failure is retried five times then dead-lettered`() {
        val orderId = 105L
        val correlationId = UUID.randomUUID().toString()
        whenever(saga.onPaymentCompleted(any(), eq(orderId))).thenThrow(QueryTimeoutException("database timeout"))

        send(eventRecord(KafkaConst.PAYMENT_EVENTS_TOPIC, KafkaConst.PAYMENT_COMPLETED_EVENT, correlationId, "{\"orderId\":$orderId}"))

        awaitDeadLetter(KafkaConst.PAYMENT_EVENTS_DLT_TOPIC, correlationId, RETRY_MESSAGE_WAIT_TIMEOUT)
        verify(saga, times(MAX_ATTEMPTS)).onPaymentCompleted(any(), eq(orderId))
    }

    @Test
    fun `onPaymentEvent - a malformed payload is dead-lettered without being dispatched or retried`() {
        val correlationId = UUID.randomUUID().toString()
        val malformedPayload = "this is not json"
        val before = committedOffset()

        send(eventRecord(KafkaConst.PAYMENT_EVENTS_TOPIC, KafkaConst.PAYMENT_COMPLETED_EVENT, correlationId, malformedPayload))

        val deadLetter = awaitDeadLetter(KafkaConst.PAYMENT_EVENTS_DLT_TOPIC, correlationId, NON_RETRYABLE_DEAD_LETTER_TIMEOUT)
        assertEquals(malformedPayload, deadLetter.value())
        verifyNoInteractions(saga)
        await().atMost(MESSAGE_WAIT_TIMEOUT)
            .untilAsserted { assertEquals(before + 1, committedOffset()) }
    }

    @Test
    fun `onPaymentEvent - a missing order is dead-lettered after a single attempt`() {
        val orderId = 106L
        val correlationId = UUID.randomUUID().toString()
        whenever(saga.onPaymentCompleted(any(), eq(orderId))).thenThrow(OrderNotFoundException(orderId))

        send(eventRecord(KafkaConst.PAYMENT_EVENTS_TOPIC, KafkaConst.PAYMENT_COMPLETED_EVENT, correlationId, "{\"orderId\":$orderId}"))

        awaitDeadLetter(KafkaConst.PAYMENT_EVENTS_DLT_TOPIC, correlationId, NON_RETRYABLE_DEAD_LETTER_TIMEOUT)
        verify(saga, times(1)).onPaymentCompleted(any(), eq(orderId))
    }

    @Test
    fun `onPaymentEvent - a domain rule violation is dead-lettered after a single attempt`() {
        val orderId = 107L
        val correlationId = UUID.randomUUID().toString()
        whenever(saga.onPaymentCompleted(any(), eq(orderId)))
            .thenThrow(OrderTransitionNotAllowedException(orderId, "confirm payment", OrderStatus.CANCELLED))

        send(eventRecord(KafkaConst.PAYMENT_EVENTS_TOPIC, KafkaConst.PAYMENT_COMPLETED_EVENT, correlationId, "{\"orderId\":$orderId}"))

        awaitDeadLetter(KafkaConst.PAYMENT_EVENTS_DLT_TOPIC, correlationId, NON_RETRYABLE_DEAD_LETTER_TIMEOUT)
        verify(saga, times(1)).onPaymentCompleted(any(), eq(orderId))
    }

    // Each consumed topic has its own DLT: a failing shipping event must land on shipping.events.DLT,
    // not on payment.events.DLT.
    @Test
    fun `onShippingEvent - a malformed payload is dead-lettered to the shipping events DLT`() {
        val correlationId = UUID.randomUUID().toString()
        val malformedPayload = "this is not json"

        send(eventRecord(KafkaConst.SHIPPING_EVENTS_TOPIC, KafkaConst.SHIPMENT_CREATED_EVENT, correlationId, malformedPayload))

        val deadLetter = awaitDeadLetter(KafkaConst.SHIPPING_EVENTS_DLT_TOPIC, correlationId, NON_RETRYABLE_DEAD_LETTER_TIMEOUT)
        assertEquals(malformedPayload, deadLetter.value())
        assertEquals(KafkaConst.SHIPPING_EVENTS_TOPIC, headerValue(deadLetter.headers(), DLT_ORIGINAL_TOPIC_HEADER))
        verifyNoInteractions(saga)
    }

    @Test
    fun `a message missing a required header is dead-lettered without being dispatched to the saga`() {
        val key = "no-headers"
        val record = ProducerRecord(KafkaConst.PAYMENT_EVENTS_TOPIC, key, "{}")
        record.headers().add(RecordHeader(KafkaConst.MESSAGE_TYPE, KafkaConst.PAYMENT_COMPLETED_EVENT.toByteArray(StandardCharsets.UTF_8)))
        // correlationId header intentionally omitted - the consumer rejects the message (InvalidMessageHeaderException)
        // before the saga is ever reached.
        send(record)

        awaitDeadLetter(KafkaConst.PAYMENT_EVENTS_DLT_TOPIC, key, NON_RETRYABLE_DEAD_LETTER_TIMEOUT)

        verifyNoInteractions(saga)
    }
}
