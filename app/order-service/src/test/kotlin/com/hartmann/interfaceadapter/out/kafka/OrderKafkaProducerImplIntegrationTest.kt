package com.hartmann.interfaceadapter.out.kafka

import com.hartmann.entities.value.Address
import com.hartmann.usecase.port.common.kafka.KafkaConst
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.header.Header
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.ImportAutoConfiguration
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration
import org.springframework.boot.kafka.autoconfigure.KafkaAutoConfiguration
import org.springframework.boot.kafka.autoconfigure.KafkaProperties
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.redpanda.RedpandaContainer
import tools.jackson.databind.ObjectMapper
import java.math.BigDecimal
import java.nio.charset.StandardCharsets
import java.time.Duration

@SpringBootTest(classes = [OrderKafkaProducerImpl::class])
@ImportAutoConfiguration(KafkaAutoConfiguration::class, JacksonAutoConfiguration::class)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_METHOD)
class OrderKafkaProducerImplIntegrationTest {

    companion object {
        private val MESSAGE_WAIT_TIMEOUT: Duration = Duration.ofSeconds(20)
        private val POLL_INTERVAL: Duration = Duration.ofSeconds(2)

        @Container
        @JvmStatic
        private val REDPANDA = RedpandaContainer("docker.redpanda.com/redpandadata/redpanda:v26.1.16")

        @DynamicPropertySource
        @JvmStatic
        fun kafkaProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.kafka.bootstrap-servers") { REDPANDA.bootstrapServers }
        }
    }

    @Autowired
    private lateinit var producer: OrderKafkaProducerImpl

    @Autowired
    private lateinit var kafkaProperties: KafkaProperties

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    private lateinit var consumer: KafkaConsumer<String, String>

    @BeforeEach
    fun setUp() {
        val consumerProps = HashMap(kafkaProperties.buildConsumerProperties())
        consumerProps[ConsumerConfig.GROUP_ID_CONFIG] = "order-producer-int-test"
        consumerProps[ConsumerConfig.AUTO_OFFSET_RESET_CONFIG] = "earliest"

        consumer = KafkaConsumer(consumerProps)
        consumer.assign(
            listOf(
                TopicPartition(KafkaConst.INVENTORY_COMMANDS_TOPIC, 0),
                TopicPartition(KafkaConst.SHIPPING_COMMANDS_TOPIC, 0),
                TopicPartition(KafkaConst.PAYMENT_COMMANDS_TOPIC, 0),
            )
        )
        consumer.seekToBeginning(consumer.assignment())
    }

    @AfterEach
    fun tearDown() {
        consumer.close()
    }

    private fun pollFor(key: String): ConsumerRecord<String, String> {
        val deadline = System.nanoTime() + MESSAGE_WAIT_TIMEOUT.toNanos()
        while (System.nanoTime() < deadline) {
            for (record in consumer.poll(POLL_INTERVAL)) {
                if (record.key() == key) {
                    return record
                }
            }
        }
        throw AssertionError("no record with key $key was polled in time")
    }

    private fun headerValue(record: ConsumerRecord<String, String>, key: String): String? {
        val header: Header? = record.headers().lastHeader(key)
        return header?.let { String(it.value(), StandardCharsets.UTF_8) }
    }

    @Test
    fun `publishCancelReservation - publishes a CancelReservation command to the inventory commands topic`() {
        val orderId = 42L

        producer.publishCancelReservation("corr-1", orderId)

        val record = pollFor(orderId.toString())
        assertEquals(KafkaConst.INVENTORY_COMMANDS_TOPIC, record.topic())
        assertEquals(orderId.toString(), record.key())
        assertEquals(KafkaConst.CANCEL_RESERVATION_COMMAND, headerValue(record, KafkaConst.MESSAGE_TYPE))
        assertEquals("corr-1", headerValue(record, KafkaConst.CORRELATION_ID))
        assertEquals(orderId, objectMapper.readTree(record.value())["orderId"].asLong())
    }

    @Test
    fun `publishCreateShipment - publishes a CreateShipment command to the shipping commands topic`() {
        val orderId = 43L
        val address = Address(city = "Kyiv", postalCode = "01001", line1 = "Khreshchatyk 1", line2 = "")

        producer.publishCreateShipment("corr-2", orderId, address)

        val record = pollFor(orderId.toString())
        assertEquals(KafkaConst.SHIPPING_COMMANDS_TOPIC, record.topic())
        assertEquals(KafkaConst.CREATE_SHIPMENT_COMMAND, headerValue(record, KafkaConst.MESSAGE_TYPE))
        val payload = objectMapper.readTree(record.value())
        assertEquals(orderId, payload["orderId"].asLong())
        assertEquals("Kyiv", payload["address"]["city"].asString())
    }

    @Test
    fun `publishRefundRequested - publishes a RefundRequested command to the payment commands topic`() {
        val orderId = 44L
        val amount = BigDecimal("40.00")

        producer.publishRefundRequested("corr-3", orderId, amount, "USD")

        val record = pollFor(orderId.toString())
        assertEquals(KafkaConst.PAYMENT_COMMANDS_TOPIC, record.topic())
        assertEquals(KafkaConst.REFUND_REQUESTED_COMMAND, headerValue(record, KafkaConst.MESSAGE_TYPE))
        val payload = objectMapper.readTree(record.value())
        assertEquals(orderId, payload["orderId"].asLong())
        assertEquals("USD", payload["currency"].asString())
    }
}
