package kafkago

import (
	"bytes"
	"context"
	"encoding/binary"
	"encoding/json"
	"errors"
	inkafka "shippingservice/adapter/in/kafka"
	"testing"
	"time"

	"shippingservice/adapter/common/kafka"

	kafkago "github.com/segmentio/kafka-go"
)

// headerValues returns the values of every header with the given key, in order.
func headerValues(headers []kafkago.Header, key string) [][]byte {
	var values [][]byte
	for _, header := range headers {
		if header.Key == key {
			values = append(values, header.Value)
		}
	}

	return values
}

// singleHeader returns the value of the only header with the given key, failing the test if there
// isn't exactly one.
func singleHeader(t *testing.T, headers []kafkago.Header, key string) []byte {
	t.Helper()

	values := headerValues(headers, key)
	if len(values) != 1 {
		t.Fatalf("header %q occurs %d times, want exactly once", key, len(values))
	}

	return values[0]
}

func TestNewDeadLetterMessage(t *testing.T) {
	sourceMessage := func() kafkago.Message {
		return kafkago.Message{
			Topic:     kafka.ShippingCommandsTopic,
			Partition: 2,
			Offset:    41,
			Key:       []byte("7"),
			Value:     []byte(`{"orderId":7}`),
			Headers: []kafkago.Header{
				{Key: kafka.MessageTypeHeader, Value: []byte(kafka.CreateShipmentCommand.String())},
				{Key: kafka.MessageIDHeader, Value: []byte("msg-1")},
				{Key: kafka.CorrelationIDHeader, Value: []byte("corr-1")},
			},
		}
	}

	t.Run("keeps the key, payload and business headers of the source message", func(t *testing.T) {
		source := sourceMessage()

		got := newDeadLetterMessage(source, "shipping-service", errors.New("boom"))

		if !bytes.Equal(got.Key, source.Key) {
			t.Errorf("key = %q, want %q", got.Key, source.Key)
		}
		if !bytes.Equal(got.Value, source.Value) {
			t.Errorf("value = %q, want %q", got.Value, source.Value)
		}
		for _, header := range source.Headers {
			if gotValue := singleHeader(t, got.Headers, header.Key); !bytes.Equal(gotValue, header.Value) {
				t.Errorf("header %q = %q, want %q", header.Key, gotValue, header.Value)
			}
		}
	})

	t.Run("describes where the source message came from and why it failed", func(t *testing.T) {
		source := sourceMessage()
		groupID := "shipping-service"
		var payload struct{}
		cause := inkafka.NonRetryable(json.Unmarshal([]byte("not json"), &payload))

		got := newDeadLetterMessage(source, groupID, cause)

		if gotTopic := string(singleHeader(t, got.Headers, kafka.DLTOriginalTopicHeader)); gotTopic != source.Topic {
			t.Errorf("original topic = %q, want %q", gotTopic, source.Topic)
		}
		if gotPartition := binary.BigEndian.Uint32(singleHeader(t, got.Headers, kafka.DLTOriginalPartitionHeader)); int(gotPartition) != source.Partition {
			t.Errorf("original partition = %d, want %d", gotPartition, source.Partition)
		}
		if gotOffset := binary.BigEndian.Uint64(singleHeader(t, got.Headers, kafka.DLTOriginalOffsetHeader)); int64(gotOffset) != source.Offset {
			t.Errorf("original offset = %d, want %d", gotOffset, source.Offset)
		}
		if gotGroup := string(singleHeader(t, got.Headers, kafka.DLTOriginalConsumerGroupHeader)); gotGroup != groupID {
			t.Errorf("original consumer group = %q, want %q", gotGroup, groupID)
		}
		if gotFQCN, wantFQCN := string(singleHeader(t, got.Headers, kafka.DLTExceptionFQCNHeader)), "*json.SyntaxError"; gotFQCN != wantFQCN {
			t.Errorf("exception type = %q, want %q", gotFQCN, wantFQCN)
		}
		if gotMessage := string(singleHeader(t, got.Headers, kafka.DLTExceptionMessageHeader)); gotMessage != cause.Error() {
			t.Errorf("exception message = %q, want %q", gotMessage, cause.Error())
		}
	})

	t.Run("replaces failure headers left by an earlier dead-lettering instead of duplicating them", func(t *testing.T) {
		source := sourceMessage()
		oldTopic := "an.older.topic"
		source.Headers = append(source.Headers, kafkago.Header{Key: kafka.DLTOriginalTopicHeader, Value: []byte(oldTopic)})

		got := newDeadLetterMessage(source, "shipping-service", errors.New("boom"))

		if gotTopic := string(singleHeader(t, got.Headers, kafka.DLTOriginalTopicHeader)); gotTopic != source.Topic {
			t.Errorf("original topic = %q, want %q", gotTopic, source.Topic)
		}
	})
}

func TestDeadLetterRetryableProducer_Send(t *testing.T) {
	message := kafkago.Message{Key: []byte("7")}

	t.Run("publishes without retrying when the producer producer succeeds", func(t *testing.T) {
		producer := &mockDeadLetterProducer{}

		err := newDeadLetterRetryableProducer(producer, time.Millisecond).Send(context.Background(), message)

		if err != nil {
			t.Errorf("err = %v, want nil", err)
		}
		if wantCalls := 1; producer.calls != wantCalls {
			t.Errorf("calls = %d, want %d", producer.calls, wantCalls)
		}
	})

	t.Run("retries a failing publish until it succeeds", func(t *testing.T) {
		failures := 2
		producer := &mockDeadLetterProducer{failFirst: failures}

		err := newDeadLetterRetryableProducer(producer, time.Millisecond).Send(context.Background(), message)

		if err != nil {
			t.Errorf("err = %v, want nil", err)
		}
		if wantCalls := failures + 1; producer.calls != wantCalls {
			t.Errorf("calls = %d, want %d", producer.calls, wantCalls)
		}
	})

	t.Run("waits the configured interval between attempts", func(t *testing.T) {
		interval := 20 * time.Millisecond
		producer := &mockDeadLetterProducer{failFirst: 1}
		start := time.Now()

		_ = newDeadLetterRetryableProducer(producer, interval).Send(context.Background(), message)

		if elapsed, wantAtLeast := time.Since(start), interval; elapsed < wantAtLeast {
			t.Errorf("elapsed = %v, want at least %v", elapsed, wantAtLeast)
		}
	})

	t.Run("stops retrying and returns the context's error when the context is cancelled between attempts", func(t *testing.T) {
		producer := &mockDeadLetterProducer{failFirst: 1 << 30}
		ctx, cancel := context.WithTimeout(context.Background(), 50*time.Millisecond)
		defer cancel()

		err := newDeadLetterRetryableProducer(producer, 10*time.Millisecond).Send(ctx, message)

		if !errors.Is(err, context.DeadlineExceeded) {
			t.Errorf("err = %v, want %v", err, context.DeadlineExceeded)
		}
		if producer.calls < 1 {
			t.Errorf("calls = %d, want at least 1", producer.calls)
		}
	})
}

func TestDeadLetterRetryableProducer_Close(t *testing.T) {
	t.Run("closes the producer producer", func(t *testing.T) {
		producer := &mockDeadLetterProducer{}

		if err := newDeadLetterRetryableProducer(producer, time.Millisecond).Close(); err != nil {
			t.Errorf("err = %v, want nil", err)
		}
		if wantCloseCalls := 1; producer.closeCalls != wantCloseCalls {
			t.Errorf("close calls = %d, want %d", producer.closeCalls, wantCloseCalls)
		}
	})
}

func TestOriginalPartitionBalancer(t *testing.T) {
	messageWithOriginalPartition := func(header []byte) kafkago.Message {
		message := kafkago.Message{Key: []byte("7")}
		if header != nil {
			message.Headers = []kafkago.Header{{Key: kafka.DLTOriginalPartitionHeader, Value: header}}
		}

		return message
	}
	partitions := []int{0, 1, 2}

	t.Run("routes to the partition the message came from", func(t *testing.T) {
		message := messageWithOriginalPartition(binary.BigEndian.AppendUint32(nil, 2))

		got := newOriginalPartitionBalancer().Balance(message, partitions...)

		if want := 2; got != want {
			t.Errorf("partition = %d, want %d", got, want)
		}
	})

	testCases := map[string]kafkago.Message{
		"hashes the key when the message has no original partition header":            messageWithOriginalPartition(nil),
		"hashes the key when the original partition doesn't exist in the DLT":         messageWithOriginalPartition(binary.BigEndian.AppendUint32(nil, 5)),
		"hashes the key when the original partition header isn't a 4-byte big-endian": messageWithOriginalPartition([]byte("2")),
	}
	for name, message := range testCases {
		t.Run(name, func(t *testing.T) {
			got := newOriginalPartitionBalancer().Balance(message, partitions...)

			want := (&kafkago.Hash{}).Balance(message, partitions...)
			if got != want {
				t.Errorf("partition = %d, want %d", got, want)
			}
		})
	}
}
