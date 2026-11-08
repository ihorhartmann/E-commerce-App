package com.hartmann.infrastructure.kafka;

import com.hartmann.adapter.common.kafka.KafkaConst;
import com.hartmann.adapter.common.metrics.FailureMetrics;
import com.hartmann.adapter.in.http.exception.HttpErrorCode;
import com.hartmann.adapter.in.kafka.KafkaFailures;
import com.hartmann.domain.exception.PaymentServiceException;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.Timer;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.listener.RecordInterceptor;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

import static com.hartmann.domain.exception.Attributes.outermostOf;

/**
 * Records every attempt at handling a message - each retry is one more - as `ecommerce.kafka.message.duration`, by its
 * topic, message type and outcome: handled, failed in a way a retry can fix, or rejected - failed for good,
 * dead-lettered without a retry. A failure is counted by its code too. The other services record the same, under the
 * same names.
 * <p>
 * Spring Kafka calls it on the listener's thread, around every delivery of a record - the first and every redelivery
 * after the error handler's back-off.
 */
public class KafkaMetricsInterceptor implements RecordInterceptor<Object, Object> {
    private static final String MESSAGE_DURATION = "ecommerce.kafka.message.duration";
    private static final String OUTCOME_SUCCESS = "success";
    private static final String OUTCOME_FAILED = "failed";
    private static final String OUTCOME_REJECTED = "rejected";
    private static final String UNKNOWN_MESSAGE_TYPE = "unknown";

    // the start of the attempt running on this listener thread
    private final ThreadLocal<Long> attemptStart = new ThreadLocal<>();

    @Override
    public ConsumerRecord<Object, Object> intercept(ConsumerRecord<Object, Object> record, Consumer<Object, Object> consumer) {
        attemptStart.set(System.nanoTime());
        return record;
    }

    @Override
    public void success(ConsumerRecord<Object, Object> record, Consumer<Object, Object> consumer) {
        recordAttemptMetric(record, OUTCOME_SUCCESS);
    }

    @Override
    public void failure(ConsumerRecord<Object, Object> record, Exception exception, Consumer<Object, Object> consumer) {
        recordAttemptMetric(record, KafkaFailures.isPermanentFailure(exception) ? OUTCOME_REJECTED : OUTCOME_FAILED);
        // a failure that isn't ours is reported as an internal error, as GlobalExceptionHandler does
        var code = outermostOf(exception).map(PaymentServiceException::code).orElse(HttpErrorCode.INTERNAL_ERROR);
        FailureMetrics.recordFailureMetric(code.code(), FailureMetrics.TRANSPORT_KAFKA);
    }

    @Override
    public void afterRecord(ConsumerRecord<Object, Object> record, Consumer<Object, Object> consumer) {
        attemptStart.remove();
    }

    private void recordAttemptMetric(ConsumerRecord<Object, Object> record, String outcome) {
        var start = attemptStart.get();
        if (start == null) {
            return;
        }
        Timer.builder(MESSAGE_DURATION)
                .tag("topic", record.topic())
                .tag("message.type", messageTypeOf(record))
                .tag("outcome", outcome)
                .register(Metrics.globalRegistry)
                .record(System.nanoTime() - start, TimeUnit.NANOSECONDS);
    }

    /** The record's messageType header, or `unknown` for a record without one. */
    static String messageTypeOf(ConsumerRecord<?, ?> record) {
        var header = record.headers().lastHeader(KafkaConst.MESSAGE_TYPE);
        return header == null ? UNKNOWN_MESSAGE_TYPE : new String(header.value(), StandardCharsets.UTF_8);
    }
}
