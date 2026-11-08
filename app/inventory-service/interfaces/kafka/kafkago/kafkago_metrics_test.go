package kafkago

import (
	"context"
	"errors"
	"sync"
	"testing"
	"time"

	"inventoryservice/application/port/common/kafka"
	"inventoryservice/domain/apperror"
	inkafka "inventoryservice/interfaces/kafka"

	"go.opentelemetry.io/otel"
	"go.opentelemetry.io/otel/attribute"
	sdkmetric "go.opentelemetry.io/otel/sdk/metric"
	"go.opentelemetry.io/otel/sdk/metric/metricdata"
)

var (
	installMetricReader sync.Once
	metricReader        *sdkmetric.ManualReader
)

// useMetricReader installs a meter provider reading into a manual reader, once - the package's instruments are bound
// to the first provider installed. Every test records under a topic of its own, as the values add up across tests.
func useMetricReader() {
	installMetricReader.Do(func() {
		metricReader = sdkmetric.NewManualReader()
		otel.SetMeterProvider(sdkmetric.NewMeterProvider(sdkmetric.WithReader(metricReader)))
	})
}

// collectMetrics returns everything recorded so far.
func collectMetrics(t *testing.T) []metricdata.Metrics {
	t.Helper()

	var resourceMetrics metricdata.ResourceMetrics
	if err := metricReader.Collect(context.Background(), &resourceMetrics); err != nil {
		t.Fatalf("failed to collect metrics: %v", err)
	}
	var all []metricdata.Metrics
	for _, scopeMetrics := range resourceMetrics.ScopeMetrics {
		all = append(all, scopeMetrics.Metrics...)
	}
	return all
}

// attemptsOf returns the attempts recorded for topic, as their attribute sets - each one once per attempt.
func attemptsOf(t *testing.T, topic string) []attribute.Set {
	t.Helper()
	var attempts []attribute.Set
	for _, metric := range collectMetrics(t) {
		histogram, ok := metric.Data.(metricdata.Histogram[float64])
		if metric.Name != "ecommerce.kafka.message.duration" || !ok {
			continue
		}
		for _, point := range histogram.DataPoints {
			if value, _ := point.Attributes.Value("topic"); value.AsString() == topic {
				for range point.Count {
					attempts = append(attempts, point.Attributes)
				}
			}
		}
	}
	return attempts
}

// counterValue returns the value of counter name for the data point carrying attribute key = value, 0 without one.
func counterValue(t *testing.T, name string, key attribute.Key, value string) int64 {
	t.Helper()
	for _, metric := range collectMetrics(t) {
		sum, ok := metric.Data.(metricdata.Sum[int64])
		if metric.Name != name || !ok {
			continue
		}
		for _, point := range sum.DataPoints {
			if got, _ := point.Attributes.Value(key); got.AsString() == value {
				return point.Value
			}
		}
	}
	return 0
}

func attributeOf(set attribute.Set, key attribute.Key) string {
	value, _ := set.Value(key)
	return value.AsString()
}

func TestRecordAttemptMetric(t *testing.T) {
	useMetricReader()

	t.Run("records a handled message as a success", func(t *testing.T) {
		topic := "success-topic"
		messageType := kafka.MessageType("HoldReservation")

		recordAttemptMetric(context.Background(), topic, messageType, time.Now(), nil)

		attempts := attemptsOf(t, topic)
		if len(attempts) != 1 {
			t.Fatalf("attempts = %d, want 1", len(attempts))
		}
		if gotOutcome := attributeOf(attempts[0], "outcome"); gotOutcome != outcomeSuccess {
			t.Errorf("outcome = %q, want %q", gotOutcome, outcomeSuccess)
		}
		if gotMessageType := attributeOf(attempts[0], "message.type"); gotMessageType != messageType.String() {
			t.Errorf("message.type = %q, want %q", gotMessageType, messageType)
		}
	})

	t.Run("records a transient failure as failed, and counts it by its code", func(t *testing.T) {
		topic := "failed-topic"
		err := apperror.ErrInternal.WithCause(errors.New("connection reset"))
		failuresBefore := counterValue(t, "ecommerce.errors", "error.code", apperror.ErrInternal.Code())

		recordAttemptMetric(context.Background(), topic, "HoldReservation", time.Now(), err)

		attempts := attemptsOf(t, topic)
		if len(attempts) != 1 {
			t.Fatalf("attempts = %d, want 1", len(attempts))
		}
		if gotOutcome := attributeOf(attempts[0], "outcome"); gotOutcome != outcomeFailed {
			t.Errorf("outcome = %q, want %q", gotOutcome, outcomeFailed)
		}
		if gotFailures := counterValue(t, "ecommerce.errors", "error.code", apperror.ErrInternal.Code()) - failuresBefore; gotFailures != 1 {
			t.Errorf("failures counted = %d, want 1", gotFailures)
		}
	})

	t.Run("records a permanent failure as rejected", func(t *testing.T) {
		topic := "rejected-topic"

		recordAttemptMetric(context.Background(), topic, "HoldReservation", time.Now(), inkafka.ErrInvalidMessageHeaders)

		attempts := attemptsOf(t, topic)
		if len(attempts) != 1 {
			t.Fatalf("attempts = %d, want 1", len(attempts))
		}
		if gotOutcome := attributeOf(attempts[0], "outcome"); gotOutcome != outcomeRejected {
			t.Errorf("outcome = %q, want %q", gotOutcome, outcomeRejected)
		}
	})

	t.Run("records a message without a messageType header as unknown", func(t *testing.T) {
		topic := "unknown-type-topic"

		recordAttemptMetric(context.Background(), topic, "", time.Now(), nil)

		attempts := attemptsOf(t, topic)
		if len(attempts) != 1 {
			t.Fatalf("attempts = %d, want 1", len(attempts))
		}
		if gotMessageType := attributeOf(attempts[0], "message.type"); gotMessageType != unknownMessageType {
			t.Errorf("message.type = %q, want %q", gotMessageType, unknownMessageType)
		}
	})
}

func TestRecordDeadLetteredMetric(t *testing.T) {
	useMetricReader()

	t.Run("counts the message under its topic", func(t *testing.T) {
		topic := "dead-lettered-topic"

		recordDeadLetteredMetric(context.Background(), topic, "HoldReservation")

		if got := counterValue(t, "ecommerce.kafka.message.dead_lettered", "topic", topic); got != 1 {
			t.Errorf("dead-lettered = %d, want 1", got)
		}
	})
}
