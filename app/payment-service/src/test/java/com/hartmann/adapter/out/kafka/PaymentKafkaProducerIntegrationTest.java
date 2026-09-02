package com.hartmann.adapter.out.kafka;

import com.hartmann.adapter.common.kafka.KafkaConst;
import com.hartmann.adapter.out.kafka.dto.OrderIdEvent;
import com.hartmann.adapter.out.kafka.dto.RefundCompletedEvent;
import com.hartmann.domain.value.Currency;
import com.hartmann.domain.value.Money;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.header.Header;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.kafka.autoconfigure.KafkaAutoConfiguration;
import org.springframework.boot.kafka.autoconfigure.KafkaProperties;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.redpanda.RedpandaContainer;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("integration")
@SpringBootTest(classes = {PaymentKafkaProducerImpl.class})
@ImportAutoConfiguration({KafkaAutoConfiguration.class, JacksonAutoConfiguration.class})
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_METHOD)
class PaymentKafkaProducerIntegrationTest {

    private static final Duration MESSAGE_WAIT_TIMEOUT = Duration.ofSeconds(20);
    private static final Duration POLL_INTERVAL = Duration.ofSeconds(2);

    @Container
    private static final RedpandaContainer REDPANDA = new RedpandaContainer("docker.redpanda.com/redpandadata/redpanda:v26.1.16");

    @DynamicPropertySource
    private static void kafkaProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", REDPANDA::getBootstrapServers);
    }

    @Autowired
    private PaymentKafkaProducerImpl producer;

    @Autowired
    private KafkaProperties kafkaProperties;

    @Autowired
    private ObjectMapper objectMapper;

    private KafkaConsumer<String, String> consumer;

    @BeforeEach
    void setUp() {
        var consumerProps = new HashMap<>(kafkaProperties.buildConsumerProperties());
        consumerProps.put(ConsumerConfig.GROUP_ID_CONFIG, "payment-producer-int-test");
        consumerProps.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");

        consumer = new KafkaConsumer<>(consumerProps);
        consumer.assign(List.of(new TopicPartition(KafkaConst.PAYMENT_EVENTS_TOPIC, 0)));
        consumer.seekToBeginning(consumer.assignment());
    }

    @AfterEach
    void tearDown() {
        consumer.close();
    }

    private ConsumerRecord<String, String> pollFor(String key) {
        var deadline = System.nanoTime() + MESSAGE_WAIT_TIMEOUT.toNanos();
        while (System.nanoTime() < deadline) {
            for (var record : consumer.poll(POLL_INTERVAL)) {
                if (record.key().equals(key)) {
                    return record;
                }
            }
        }
        throw new AssertionError("no record with key " + key + " was polled in time");
    }

    private static String headerValue(ConsumerRecord<String, String> record, String key) {
        Header header = record.headers().lastHeader(key);
        return header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
    }

    @Test
    void publishPaymentCompleted_publishesAPaymentCompletedEventWithTheCorrectTypeKeyAndPayload() {
        var orderId = 42L;

        producer.publishPaymentCompleted(orderId);

        var record = pollFor(String.valueOf(orderId));
        assertThat(record.topic()).isEqualTo(KafkaConst.PAYMENT_EVENTS_TOPIC);
        assertThat(record.key()).isEqualTo(String.valueOf(orderId));
        assertThat(headerValue(record, KafkaConst.MESSAGE_TYPE)).isEqualTo(KafkaConst.PAYMENT_COMPLETED_EVENT);
        assertThat(headerValue(record, KafkaConst.CORRELATION_ID)).isEqualTo(String.valueOf(orderId));
        assertThat(objectMapper.readValue(record.value(), OrderIdEvent.class))
                .isEqualTo(new OrderIdEvent(orderId));
    }

    @Test
    void publishRefundCompleted_publishesARefundCompletedEventWithTheMappedAmount() {
        var orderId = 13L;
        var amount = new Money(new BigDecimal("40.00"), Currency.USD);

        producer.publishRefundCompleted(orderId, amount);

        var record = pollFor(String.valueOf(orderId));
        assertThat(record.topic()).isEqualTo(KafkaConst.PAYMENT_EVENTS_TOPIC);
        assertThat(record.key()).isEqualTo(String.valueOf(orderId));
        assertThat(headerValue(record, KafkaConst.MESSAGE_TYPE)).isEqualTo(KafkaConst.REFUND_COMPLETED_EVENT);
        assertThat(objectMapper.readValue(record.value(), RefundCompletedEvent.class))
                .isEqualTo(RefundCompletedEvent.from(orderId, amount));
    }
}
