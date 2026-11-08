package com.hartmann.infrastructure.kafka;

import com.hartmann.adapter.common.kafka.KafkaConst;
import com.hartmann.adapter.in.http.exception.HttpErrorCode;
import com.hartmann.application.port.out.repository.exception.PaymentNotFoundException;
import com.hartmann.application.port.out.repository.exception.RepositoryErrorCode;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.apache.kafka.common.record.TimestampType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
@TestInstance(TestInstance.Lifecycle.PER_METHOD)
class KafkaMetricsInterceptorTest {
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final KafkaMetricsInterceptor interceptor = new KafkaMetricsInterceptor();

    @Mock
    private Consumer<Object, Object> consumer;

    @BeforeEach
    void addRegistry() {
        Metrics.addRegistry(registry);
    }

    @AfterEach
    void removeRegistry() {
        Metrics.removeRegistry(registry);
    }

    private static ConsumerRecord<Object, Object> record(String topic, String messageType) {
        var headers = new RecordHeaders();
        if (messageType != null) {
            headers.add(KafkaConst.MESSAGE_TYPE, messageType.getBytes(StandardCharsets.UTF_8));
        }
        return new ConsumerRecord<>(topic, 0, 0L, 0L, TimestampType.CREATE_TIME, 0, 0, "key", "value", headers, Optional.empty());
    }

    private long attempts(String topic, String messageType, String outcome) {
        var timer = registry.find("ecommerce.kafka.message.duration")
                .tags("topic", topic, "message.type", messageType, "outcome", outcome)
                .timer();
        return timer == null ? 0 : timer.count();
    }

    // the meters of the global registry outlive a test, and come into each new registry, with nothing recorded yet
    private long recordedAttempts() {
        return registry.find("ecommerce.kafka.message.duration").timers().stream().mapToLong(Timer::count).sum();
    }

    private double failures(String code) {
        var counter = registry.find("ecommerce.errors").tags("error.code", code, "transport", "kafka").counter();
        return counter == null ? 0 : counter.count();
    }

    @Test
    void success_recordsTheAttemptAsASuccessByItsTopicAndMessageType() {
        var record = record(KafkaConst.PAYMENT_COMMANDS_TOPIC, KafkaConst.REFUND_REQUESTED_COMMAND);

        interceptor.intercept(record, consumer);
        interceptor.success(record, consumer);

        assertThat(attempts(KafkaConst.PAYMENT_COMMANDS_TOPIC, KafkaConst.REFUND_REQUESTED_COMMAND, "success")).isEqualTo(1);
        assertThat(registry.find("ecommerce.errors").counters().stream().mapToDouble(Counter::count).sum()).isZero();
        verifyNoInteractions(consumer);
    }

    @Test
    void success_recordsNothingForARecordThatWasNotIntercepted() {
        var record = record(KafkaConst.PAYMENT_COMMANDS_TOPIC, KafkaConst.REFUND_REQUESTED_COMMAND);

        interceptor.success(record, consumer);

        assertThat(recordedAttempts()).isZero();
        verifyNoInteractions(consumer);
    }

    @Test
    void failure_recordsAFailureARetryCanFixAsFailedAndCountsItAsAnInternalError() {
        var record = record(KafkaConst.PAYMENT_COMMANDS_TOPIC, KafkaConst.REFUND_REQUESTED_COMMAND);

        interceptor.intercept(record, consumer);
        interceptor.failure(record, new RuntimeException("connection reset"), consumer);

        assertThat(attempts(KafkaConst.PAYMENT_COMMANDS_TOPIC, KafkaConst.REFUND_REQUESTED_COMMAND, "failed")).isEqualTo(1);
        assertThat(failures(HttpErrorCode.INTERNAL_ERROR.code())).isEqualTo(1);
        verifyNoInteractions(consumer);
    }

    @Test
    void failure_recordsAPermanentFailureAsRejectedAndCountsItByItsCode() {
        var record = record(KafkaConst.PAYMENT_COMMANDS_TOPIC, KafkaConst.REFUND_REQUESTED_COMMAND);

        interceptor.intercept(record, consumer);
        interceptor.failure(record, PaymentNotFoundException.withOrderId(7L), consumer);

        assertThat(attempts(KafkaConst.PAYMENT_COMMANDS_TOPIC, KafkaConst.REFUND_REQUESTED_COMMAND, "rejected")).isEqualTo(1);
        assertThat(failures(RepositoryErrorCode.PAYMENT_NOT_FOUND.code())).isEqualTo(1);
        verifyNoInteractions(consumer);
    }

    @Test
    void success_recordsARecordWithoutAMessageTypeHeaderAsUnknown() {
        var record = record(KafkaConst.PAYMENT_COMMANDS_TOPIC, null);

        interceptor.intercept(record, consumer);
        interceptor.success(record, consumer);

        assertThat(attempts(KafkaConst.PAYMENT_COMMANDS_TOPIC, "unknown", "success")).isEqualTo(1);
        verifyNoInteractions(consumer);
    }

    @Test
    void afterRecord_forgetsTheAttemptStartSoNothingMoreIsRecordedForIt() {
        var record = record(KafkaConst.PAYMENT_COMMANDS_TOPIC, KafkaConst.REFUND_REQUESTED_COMMAND);

        interceptor.intercept(record, consumer);
        interceptor.afterRecord(record, consumer);
        interceptor.success(record, consumer);

        assertThat(recordedAttempts()).isZero();
        verifyNoInteractions(consumer);
    }
}
