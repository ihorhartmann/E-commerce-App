package kafkago

import (
	"context"
	"errors"
	inkafka "inventoryservice/interfaces/kafka"
	"testing"
	"time"

	"inventoryservice/application/port/common/kafka"

	kafkago "github.com/segmentio/kafka-go"
)

// mockDeadLetterProducer records the messages published to it, failing the first failFirst calls.
type mockDeadLetterProducer struct {
	deadLetterProducer
	failFirst  int
	calls      int
	closeCalls int
	published  []kafkago.Message
}

func (f *mockDeadLetterProducer) Send(_ context.Context, message kafkago.Message) error {
	f.calls++
	if f.calls <= f.failFirst {
		return errors.New("dead-letter topic unavailable")
	}

	f.published = append(f.published, message)
	return nil
}

func (f *mockDeadLetterProducer) Close() error {
	f.closeCalls++
	return nil
}

func TestParseHeaders(t *testing.T) {
	t.Run("extracts messageType, messageId and correlationId when all headers are present", func(t *testing.T) {
		wantMessageType := kafka.CancelReservationCommand
		wantMessageID := "msg-1"
		wantCorrelationID := "corr-1"
		headers := []kafkago.Header{
			{Key: kafka.MessageTypeHeader, Value: []byte(wantMessageType.String())},
			{Key: kafka.MessageIDHeader, Value: []byte(wantMessageID)},
			{Key: kafka.CorrelationIDHeader, Value: []byte(wantCorrelationID)},
		}

		gotMessageType, gotMessageID, gotCorrelationID, err := parseHeaders(headers)
		if err != nil {
			t.Fatalf("err = %v, want nil", err)
		}
		if gotMessageType != wantMessageType {
			t.Errorf("messageType = %v, want %v", gotMessageType, wantMessageType)
		}
		if gotMessageID != wantMessageID {
			t.Errorf("messageID = %v, want %v", gotMessageID, wantMessageID)
		}
		if gotCorrelationID != wantCorrelationID {
			t.Errorf("correlationID = %v, want %v", gotCorrelationID, wantCorrelationID)
		}
	})

	t.Run("ignores unknown headers while extracting the required ones", func(t *testing.T) {
		wantMessageType := kafka.CancelReservationCommand
		wantMessageID := "msg-1"
		wantCorrelationID := "corr-1"
		headers := []kafkago.Header{
			{Key: "someOtherHeader", Value: []byte("ignored")},
			{Key: kafka.MessageTypeHeader, Value: []byte(wantMessageType.String())},
			{Key: kafka.MessageIDHeader, Value: []byte(wantMessageID)},
			{Key: kafka.CorrelationIDHeader, Value: []byte(wantCorrelationID)},
		}

		gotMessageType, gotMessageID, gotCorrelationID, err := parseHeaders(headers)
		if err != nil {
			t.Fatalf("err = %v, want nil", err)
		}
		if gotMessageType != wantMessageType {
			t.Errorf("messageType = %v, want %v", gotMessageType, wantMessageType)
		}
		if gotMessageID != wantMessageID {
			t.Errorf("messageID = %v, want %v", gotMessageID, wantMessageID)
		}
		if gotCorrelationID != wantCorrelationID {
			t.Errorf("correlationID = %v, want %v", gotCorrelationID, wantCorrelationID)
		}
	})

	t.Run("returns an error when the messageType header is missing", func(t *testing.T) {
		headers := []kafkago.Header{
			{Key: kafka.MessageIDHeader, Value: []byte("msg-1")},
			{Key: kafka.CorrelationIDHeader, Value: []byte("corr-1")},
		}

		if _, _, _, err := parseHeaders(headers); err == nil {
			t.Error("err = nil, want an error")
		}
	})

	t.Run("returns an error when the messageId header is missing", func(t *testing.T) {
		headers := []kafkago.Header{
			{Key: kafka.MessageTypeHeader, Value: []byte(kafka.CancelReservationCommand.String())},
			{Key: kafka.CorrelationIDHeader, Value: []byte("corr-1")},
		}

		if _, _, _, err := parseHeaders(headers); err == nil {
			t.Error("err = nil, want an error")
		}
	})

	t.Run("returns an error when the correlationId header is missing", func(t *testing.T) {
		headers := []kafkago.Header{
			{Key: kafka.MessageTypeHeader, Value: []byte(kafka.CancelReservationCommand.String())},
			{Key: kafka.MessageIDHeader, Value: []byte("msg-1")},
		}

		if _, _, _, err := parseHeaders(headers); err == nil {
			t.Error("err = nil, want an error")
		}
	})

	t.Run("returns an error when no headers are present", func(t *testing.T) {
		if _, _, _, err := parseHeaders(nil); err == nil {
			t.Error("err = nil, want an error")
		}
	})
}

func TestKafkaGoMessageConsumer_process(t *testing.T) {
	validMessage := func() kafkago.Message {
		return kafkago.Message{
			Topic:     kafka.InventoryCommandsTopic,
			Partition: 0,
			Offset:    5,
			Key:       []byte("7"),
			Value:     []byte(`{"orderId":7}`),
			Headers: []kafkago.Header{
				{Key: kafka.MessageTypeHeader, Value: []byte(kafka.CancelReservationCommand.String())},
				{Key: kafka.MessageIDHeader, Value: []byte("msg-1")},
				{Key: kafka.CorrelationIDHeader, Value: []byte("corr-1")},
			},
		}
	}
	newConsumer := func(producer deadLetterProducer) *kafkaGoMessageConsumer {
		return &kafkaGoMessageConsumer{
			groupID:            "inventory-service",
			deadLetterProducer: producer,
			retryDelays:        backoffDelays(maxRetries, time.Millisecond),
		}
	}

	t.Run("returns true without dead-lettering when the handler succeeds", func(t *testing.T) {
		producer := &mockDeadLetterProducer{}
		gotHandlerCalls := 0

		got := newConsumer(producer).process(context.Background(), validMessage(), func(context.Context, kafka.MessageType, string, string, []byte) error {
			gotHandlerCalls++
			return nil
		})

		if !got {
			t.Error("process = false, want true")
		}
		if wantHandlerCalls := 1; gotHandlerCalls != wantHandlerCalls {
			t.Errorf("handler calls = %d, want %d", gotHandlerCalls, wantHandlerCalls)
		}
		if wantPublishCalls := 0; producer.calls != wantPublishCalls {
			t.Errorf("dead-letter publishes = %d, want %d", producer.calls, wantPublishCalls)
		}
	})

	t.Run("retries a transient failure and returns true without dead-lettering once the handler succeeds", func(t *testing.T) {
		producer := &mockDeadLetterProducer{}
		failures := 2
		gotHandlerCalls := 0

		got := newConsumer(producer).process(context.Background(), validMessage(), func(context.Context, kafka.MessageType, string, string, []byte) error {
			gotHandlerCalls++
			if gotHandlerCalls <= failures {
				return errors.New("transient failure")
			}
			return nil
		})

		if !got {
			t.Error("process = false, want true")
		}
		if wantHandlerCalls := failures + 1; gotHandlerCalls != wantHandlerCalls {
			t.Errorf("handler calls = %d, want %d", gotHandlerCalls, wantHandlerCalls)
		}
		if wantPublishCalls := 0; producer.calls != wantPublishCalls {
			t.Errorf("dead-letter publishes = %d, want %d", producer.calls, wantPublishCalls)
		}
	})

	t.Run("dead-letters the message after every attempt fails", func(t *testing.T) {
		producer := &mockDeadLetterProducer{}
		source := validMessage()
		gotHandlerCalls := 0

		got := newConsumer(producer).process(context.Background(), source, func(context.Context, kafka.MessageType, string, string, []byte) error {
			gotHandlerCalls++
			return errors.New("transient failure")
		})

		if !got {
			t.Error("process = false, want true")
		}
		if wantHandlerCalls := maxRetries + 1; gotHandlerCalls != wantHandlerCalls {
			t.Errorf("handler calls = %d, want %d", gotHandlerCalls, wantHandlerCalls)
		}
		if wantPublishCalls := 1; producer.calls != wantPublishCalls {
			t.Fatalf("dead-letter publishes = %d, want %d", producer.calls, wantPublishCalls)
		}
		if gotTopic := string(singleHeader(t, producer.published[0].Headers, kafka.DLTOriginalTopicHeader)); gotTopic != source.Topic {
			t.Errorf("original topic = %q, want %q", gotTopic, source.Topic)
		}
	})

	t.Run("dead-letters a non-retryable failure after a single attempt", func(t *testing.T) {
		producer := &mockDeadLetterProducer{}
		gotHandlerCalls := 0

		got := newConsumer(producer).process(context.Background(), validMessage(), func(context.Context, kafka.MessageType, string, string, []byte) error {
			gotHandlerCalls++
			return inkafka.NonRetryable(errors.New("permanent failure"))
		})

		if !got {
			t.Error("process = false, want true")
		}
		if wantHandlerCalls := 1; gotHandlerCalls != wantHandlerCalls {
			t.Errorf("handler calls = %d, want %d", gotHandlerCalls, wantHandlerCalls)
		}
		if wantPublishCalls := 1; producer.calls != wantPublishCalls {
			t.Errorf("dead-letter publishes = %d, want %d", producer.calls, wantPublishCalls)
		}
	})

	t.Run("dead-letters a message with invalid headers without invoking the handler", func(t *testing.T) {
		producer := &mockDeadLetterProducer{}
		message := validMessage()
		message.Headers = nil
		gotHandlerCalls := 0

		got := newConsumer(producer).process(context.Background(), message, func(context.Context, kafka.MessageType, string, string, []byte) error {
			gotHandlerCalls++
			return nil
		})

		if !got {
			t.Error("process = false, want true")
		}
		if wantHandlerCalls := 0; gotHandlerCalls != wantHandlerCalls {
			t.Errorf("handler calls = %d, want %d", gotHandlerCalls, wantHandlerCalls)
		}
		if wantPublishCalls := 1; producer.calls != wantPublishCalls {
			t.Errorf("dead-letter publishes = %d, want %d", producer.calls, wantPublishCalls)
		}
	})

	t.Run("returns false without dead-lettering when the context is cancelled while waiting to retry the handler", func(t *testing.T) {
		producer := &mockDeadLetterProducer{}
		consumer := newConsumer(producer)
		consumer.retryDelays = []time.Duration{time.Hour}
		ctx, cancel := context.WithCancel(context.Background())
		gotHandlerCalls := 0

		got := consumer.process(ctx, validMessage(), func(context.Context, kafka.MessageType, string, string, []byte) error {
			gotHandlerCalls++
			cancel()
			return errors.New("transient failure")
		})

		if got {
			t.Error("process = true, want false")
		}
		if wantHandlerCalls := 1; gotHandlerCalls != wantHandlerCalls {
			t.Errorf("handler calls = %d, want %d", gotHandlerCalls, wantHandlerCalls)
		}
		if wantPublishCalls := 0; producer.calls != wantPublishCalls {
			t.Errorf("dead-letter publishes = %d, want %d", producer.calls, wantPublishCalls)
		}
	})

	// Retrying a failed dead-letter publish is now deadLetterRetryableProducer's own responsibility
	// (see TestDeadLetterRetryableProducer_Send in kafkago_dead_letter_test.go) - process only needs to
	// react to whatever Send finally returns.
	t.Run("returns false without dead-lettering when the dead-letter publish fails", func(t *testing.T) {
		producer := &mockDeadLetterProducer{failFirst: 1 << 30}

		got := newConsumer(producer).process(context.Background(), validMessage(), func(context.Context, kafka.MessageType, string, string, []byte) error {
			return inkafka.NonRetryable(errors.New("permanent failure"))
		})

		if got {
			t.Error("process = true, want false")
		}
		if wantPublishCalls := 1; producer.calls != wantPublishCalls {
			t.Errorf("dead-letter publishes = %d, want %d", producer.calls, wantPublishCalls)
		}
	})
}
