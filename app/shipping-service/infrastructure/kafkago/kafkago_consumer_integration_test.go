//go:build integration

package kafkago_test

import (
	"context"
	"encoding/binary"
	"encoding/json"
	"errors"
	"io"
	"log"
	"os"
	incommon "shippingservice/adapter/in/kafka"
	"strconv"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"shippingservice/adapter/common/kafka"
	"shippingservice/adapter/out/kafka/common"
	"shippingservice/application/port/out/outbox/dto"
	"shippingservice/infrastructure/kafkago"

	"github.com/google/uuid"
	kafkagoclient "github.com/segmentio/kafka-go"
	"github.com/testcontainers/testcontainers-go/modules/redpanda"
)

const (
	redpandaImage = "docker.redpanda.com/redpandadata/redpanda:v26.1.16"

	// messageWaitTimeout bounds how long a subtest waits for an expected
	// message or event before failing.
	messageWaitTimeout = 20 * time.Second

	// noExtraMessageWait is how long a subtest waits to make sure no
	// unexpected extra message arrives, before concluding none will.
	noExtraMessageWait = 5 * time.Second

	// brokerCallTimeout bounds a single round trip to the broker (creating
	// a topic, fetching a committed offset).
	brokerCallTimeout = 10 * time.Second

	// offsetPollInterval is the pause between committed-offset checks in
	// waitForCommittedOffset.
	offsetPollInterval = 50 * time.Millisecond

	// retryMessageWaitTimeout bounds how long a subtest waits for a failing message to be dead-lettered
	// after going through the full back-off (1s + 2s + 4s + 8s = 15s), so messageWaitTimeout would
	// leave too little margin.
	retryMessageWaitTimeout = 45 * time.Second

	// nonRetryableDeadLetterTimeout is deliberately shorter than that 15s back-off: a non-retryable
	// failure showing up in the DLT within this window is what proves it was not retried.
	nonRetryableDeadLetterTimeout = 10 * time.Second

	// maxAttempts is 1 initial attempt + 4 retries.
	maxAttempts = 5
)

var brokerAddr string

func TestMain(m *testing.M) {
	ctx := context.Background()

	container, err := redpanda.Run(ctx, redpandaImage)
	if err != nil {
		log.Fatalf("failed to start redpanda container: %v", err)
	}

	broker, err := container.KafkaSeedBroker(ctx)
	if err != nil {
		if terminateErr := container.Terminate(ctx); terminateErr != nil {
			log.Printf("failed to terminate redpanda container: %v", terminateErr)
		}
		log.Fatalf("failed to resolve kafka seed broker: %v", err)
	}
	brokerAddr = broker

	code := m.Run()
	if err = container.Terminate(ctx); err != nil {
		log.Printf("failed to terminate redpanda container: %v", err)
	}
	os.Exit(code)
}

func createTopic(t *testing.T, topic string) {
	t.Helper()

	client := &kafkagoclient.Client{Addr: kafkagoclient.TCP(brokerAddr)}
	ctx, cancel := context.WithTimeout(context.Background(), brokerCallTimeout)
	defer cancel()

	resp, err := client.CreateTopics(ctx, &kafkagoclient.CreateTopicsRequest{
		Addr:   client.Addr,
		Topics: []kafkagoclient.TopicConfig{{Topic: topic, NumPartitions: 1, ReplicationFactor: 1}},
	})
	if err != nil {
		t.Fatalf("failed to create topic %q: %v", topic, err)
	}
	if topicErr := resp.Errors[topic]; topicErr != nil {
		t.Fatalf("failed to create topic %q: %v", topic, topicErr)
	}
}

// waitForCommittedOffset polls the broker until the consumer group's
// committed offset for the topic's only partition reaches want, instead of
// guessing how long a commit takes. It replaces a blind sleep before
// Cleanup closes the consumer that issued the commit - closing while a
// commit is still in flight can hang Close() indefinitely.
func waitForCommittedOffset(t *testing.T, group, topic string, want int64) {
	t.Helper()

	client := &kafkagoclient.Client{Addr: kafkagoclient.TCP(brokerAddr)}
	deadline := time.Now().Add(messageWaitTimeout)
	for {
		ctx, cancel := context.WithTimeout(context.Background(), brokerCallTimeout)
		resp, err := client.OffsetFetch(ctx, &kafkagoclient.OffsetFetchRequest{
			Addr:    client.Addr,
			GroupID: group,
			Topics:  map[string][]int{topic: {0}},
		})
		cancel()

		if err == nil && resp.Error == nil {
			if partitions := resp.Topics[topic]; len(partitions) > 0 && partitions[0].Error == nil && partitions[0].CommittedOffset >= want {
				return
			}
		}
		if time.Now().After(deadline) {
			t.Fatalf("committed offset for group %q topic %q did not reach %d in time", group, topic, want)
		}
		time.Sleep(offsetPollInterval)
	}
}

// committedOffset reads the consumer group's committed offset for the topic's only partition straight
// from the broker, or -1 if the group has none yet.
func committedOffset(t *testing.T, group, topic string) int64 {
	t.Helper()

	client := &kafkagoclient.Client{Addr: kafkagoclient.TCP(brokerAddr)}
	ctx, cancel := context.WithTimeout(context.Background(), brokerCallTimeout)
	defer cancel()

	resp, err := client.OffsetFetch(ctx, &kafkagoclient.OffsetFetchRequest{
		Addr:    client.Addr,
		GroupID: group,
		Topics:  map[string][]int{topic: {0}},
	})
	if err != nil || resp.Error != nil {
		t.Fatalf("failed to fetch the committed offset for group %q topic %q: %v / %v", group, topic, err, resp)
	}
	partitions := resp.Topics[topic]
	if len(partitions) == 0 || partitions[0].Error != nil {
		return -1
	}

	return partitions[0].CommittedOffset
}

// awaitDeadLetter reads the dead-letter topic's only partition from the start until a message with the
// given key shows up - the same view a redrive tool would have - and fails the test if none does
// within timeout.
func awaitDeadLetter(t *testing.T, dltTopic, key string, timeout time.Duration) kafkagoclient.Message {
	t.Helper()

	reader := kafkagoclient.NewReader(kafkagoclient.ReaderConfig{
		Brokers:   []string{brokerAddr},
		Topic:     dltTopic,
		Partition: 0,
		MaxWait:   200 * time.Millisecond,
	})
	defer closeQuietly(t, "dead-letter reader", reader)
	if err := reader.SetOffset(kafkagoclient.FirstOffset); err != nil {
		t.Fatalf("failed to position the dead-letter reader: %v", err)
	}

	ctx, cancel := context.WithTimeout(context.Background(), timeout)
	defer cancel()
	for {
		message, err := reader.ReadMessage(ctx)
		if err != nil {
			t.Fatalf("no message with key %q on %q within %v: %v", key, dltTopic, timeout, err)
		}
		if string(message.Key) == key {
			return message
		}
	}
}

func headerValue(headers []kafkagoclient.Header, name string) []byte {
	for _, header := range headers {
		if header.Key == name {
			return header.Value
		}
	}

	return nil
}

// closeQuietly closes c. If that fails, it just logs the error instead of
// failing the test - a cleanup error shouldn't turn a passing test into a
// failing one.
func closeQuietly(t *testing.T, name string, c io.Closer) {
	t.Helper()
	if err := c.Close(); err != nil {
		t.Logf("failed to close %s: %v", name, err)
	}
}

// sendMessage marshals payload and publishes it as a single-message batch, mirroring how
// OutboxMessageRelay publishes a real outbox row - this test only needs a message on the wire for
// the consumer to receive, not the outbox itself.
func sendMessage(t *testing.T, producer common.KafkaMessageProducer, messageType kafka.MessageType, correlationID string, key string, payload any) {
	t.Helper()
	message, err := dto.NewOutboxMessage(correlationID, messageType.String(), key, payload, "")
	if err != nil {
		t.Fatalf("failed to build outbox message: %v", err)
	}
	// The database assigns MessageID on a real insert; this test bypasses the outbox entirely, so
	// it mints one itself to stand in for that.
	message.MessageID = uuid.New()

	if err = producer.SendBatch([]*dto.OutboxMessage{&message}); err != nil {
		t.Fatalf("failed to send message: %v", err)
	}
}

type consumedMessage struct {
	messageType   kafka.MessageType
	messageID     uuid.UUID
	correlationID string
	payload       []byte
}

func TestKafkaGoMessageConsumer_Integration(t *testing.T) {
	t.Run("delivers the produced message with correct headers and payload", func(t *testing.T) {
		topic := uuid.New().String()
		group := uuid.New().String()
		createTopic(t, topic)

		producer := kafkago.NewKafkaGoMessageProducer([]string{brokerAddr}, topic)
		t.Cleanup(func() { closeQuietly(t, "producer", producer) })

		type payload struct {
			OrderID uint64 `json:"orderId"`
		}
		wantMessageType := kafka.CreateShipmentCommand
		wantCorrelationID := uuid.New().String()
		orderId := uint64(42)
		wantPayload := payload{OrderID: orderId}

		sendMessage(t, producer, wantMessageType, wantCorrelationID, strconv.FormatUint(orderId, 10), wantPayload)

		consumer := kafkago.NewKafkaGoMessageConsumer([]string{brokerAddr}, topic, group)
		t.Cleanup(func() { closeQuietly(t, "consumer", consumer) })
		received := make(chan consumedMessage, 1)
		consumer.ConsumeAsync(func(_ context.Context, mt kafka.MessageType, id uuid.UUID, corr string, p []byte) error {
			received <- consumedMessage{mt, id, corr, p}
			return nil
		})

		select {
		case msg := <-received:
			if msg.messageType != wantMessageType {
				t.Errorf("messageType = %v, want %v", msg.messageType, wantMessageType)
			}
			if msg.correlationID != wantCorrelationID {
				t.Errorf("correlationID = %v, want %v", msg.correlationID, wantCorrelationID)
			}
			if msg.messageID == uuid.Nil {
				t.Errorf("messageID = %v, want a non-nil uuid", msg.messageID)
			}
			var gotPayload payload
			if err := json.Unmarshal(msg.payload, &gotPayload); err != nil {
				t.Fatalf("failed to unmarshal payload: %v", err)
			}
			if gotPayload != wantPayload {
				t.Errorf("payload = %+v, want %+v", gotPayload, wantPayload)
			}
		case <-time.After(messageWaitTimeout):
			t.Fatal("timed out waiting for the message to be consumed")
		}

		// The handler signals as soon as it is invoked, before the
		// background commit actually happens - wait for the broker to
		// confirm the commit landed before Cleanup closes the consumer.
		waitForCommittedOffset(t, group, topic, 1)
	})

	t.Run("processes multiple messages from the same consumer instance in order", func(t *testing.T) {
		topic := uuid.New().String()
		group := uuid.New().String()
		createTopic(t, topic)

		producer := kafkago.NewKafkaGoMessageProducer([]string{brokerAddr}, topic)
		t.Cleanup(func() { closeQuietly(t, "producer", producer) })

		correlationID1 := uuid.New().String()
		correlationID2 := uuid.New().String()
		sendMessage(t, producer, kafka.CreateShipmentCommand, correlationID1, "1", map[string]any{"n": 1})
		sendMessage(t, producer, kafka.CreateShipmentCommand, correlationID2, "2", map[string]any{"n": 2})

		consumer := kafkago.NewKafkaGoMessageConsumer([]string{brokerAddr}, topic, group)
		t.Cleanup(func() { closeQuietly(t, "consumer", consumer) })
		received := make(chan consumedMessage, 2)
		consumer.ConsumeAsync(func(_ context.Context, mt kafka.MessageType, id uuid.UUID, corr string, p []byte) error {
			received <- consumedMessage{mt, id, corr, p}
			return nil
		})

		var gotCorrelationIDs []string
		for i := range 2 {
			select {
			case msg := <-received:
				gotCorrelationIDs = append(gotCorrelationIDs, msg.correlationID)
			case <-time.After(messageWaitTimeout):
				t.Fatalf("timed out waiting for message %d to be processed", i+1)
			}
		}

		wantCorrelationIDs := []string{correlationID1, correlationID2}
		if gotCorrelationIDs[0] != wantCorrelationIDs[0] || gotCorrelationIDs[1] != wantCorrelationIDs[1] {
			t.Errorf("correlationIDs = %v, want %v (in order)", gotCorrelationIDs, wantCorrelationIDs)
		}
		waitForCommittedOffset(t, group, topic, 2)
	})

	t.Run("commits the offset by exactly one message after successful handling", func(t *testing.T) {
		topic := uuid.New().String()
		group := uuid.New().String()
		createTopic(t, topic)

		producer := kafkago.NewKafkaGoMessageProducer([]string{brokerAddr}, topic)
		t.Cleanup(func() { closeQuietly(t, "producer", producer) })

		// The second message is only sent after the first consumer has
		// closed, so consumer1 can only ever see the first message - if
		// both were sent upfront, consumer1's background loop could fetch
		// and commit the second one too before the test gets a chance to
		// close it, making the "advances by exactly one" assertion racy.
		correlationID1 := uuid.New().String()
		sendMessage(t, producer, kafka.CreateShipmentCommand, correlationID1, "1", map[string]any{"n": 1})

		consumer1 := kafkago.NewKafkaGoMessageConsumer([]string{brokerAddr}, topic, group)
		t.Cleanup(func() { closeQuietly(t, "consumer1", consumer1) })
		processed1 := make(chan struct{})
		consumer1.ConsumeAsync(func(_ context.Context, _ kafka.MessageType, _ uuid.UUID, _ string, _ []byte) error {
			close(processed1)
			return nil
		})

		select {
		case <-processed1:
		case <-time.After(messageWaitTimeout):
			t.Fatal("timed out waiting for the first message to be processed")
		}
		// Wait for the broker to confirm the commit landed before closing,
		// since the handler signals before the commit actually happens.
		waitForCommittedOffset(t, group, topic, 1)
		if err := consumer1.Close(); err != nil {
			t.Fatalf("failed to close the first consumer: %v", err)
		}

		correlationID2 := uuid.New().String()
		sendMessage(t, producer, kafka.CreateShipmentCommand, correlationID2, "2", map[string]any{"n": 2})

		consumer2 := kafkago.NewKafkaGoMessageConsumer([]string{brokerAddr}, topic, group)
		t.Cleanup(func() { closeQuietly(t, "consumer2", consumer2) })
		received2 := make(chan consumedMessage, 1)
		consumer2.ConsumeAsync(func(_ context.Context, mt kafka.MessageType, id uuid.UUID, corr string, p []byte) error {
			received2 <- consumedMessage{mt, id, corr, p}
			return nil
		})

		select {
		case msg := <-received2:
			if msg.correlationID != correlationID2 {
				t.Errorf("correlationID = %v, want %v (offset should have advanced past the first message)", msg.correlationID, correlationID2)
			}
		case <-time.After(messageWaitTimeout):
			t.Fatal("timed out waiting for the second consumer to receive a message")
		}
		// See the comment on the wait above: Cleanup must not close
		// consumer2 until its commit has actually landed on the broker.
		waitForCommittedOffset(t, group, topic, 2)
	})

	t.Run("does not commit or dead-letter a message whose retries are cut short by shutdown, so it is redelivered", func(t *testing.T) {
		topic := uuid.New().String()
		group := uuid.New().String()
		createTopic(t, topic)
		createTopic(t, topic+kafka.DLTSuffix)

		producer := kafkago.NewKafkaGoMessageProducer([]string{brokerAddr}, topic)
		t.Cleanup(func() { closeQuietly(t, "producer", producer) })

		correlationID := uuid.New().String()
		sendMessage(t, producer, kafka.CreateShipmentCommand, correlationID, "1", map[string]any{"n": 1})

		consumer1 := kafkago.NewKafkaGoMessageConsumer([]string{brokerAddr}, topic, group)
		t.Cleanup(func() { closeQuietly(t, "consumer1", consumer1) })
		received1 := make(chan struct{})
		var closeReceived1 sync.Once
		consumer1.ConsumeAsync(func(_ context.Context, _ kafka.MessageType, _ uuid.UUID, _ string, _ []byte) error {
			closeReceived1.Do(func() { close(received1) })
			return errors.New("simulated handler failure")
		})

		select {
		case <-received1:
		case <-time.After(messageWaitTimeout):
			t.Fatal("timed out waiting for the message to be processed")
		}
		if err := consumer1.Close(); err != nil {
			t.Fatalf("failed to close the first consumer: %v", err)
		}

		consumer2 := kafkago.NewKafkaGoMessageConsumer([]string{brokerAddr}, topic, group)
		t.Cleanup(func() { closeQuietly(t, "consumer2", consumer2) })
		received2 := make(chan consumedMessage, 1)
		consumer2.ConsumeAsync(func(_ context.Context, mt kafka.MessageType, id uuid.UUID, corr string, p []byte) error {
			received2 <- consumedMessage{mt, id, corr, p}
			return nil
		})

		select {
		case msg := <-received2:
			if msg.correlationID != correlationID {
				t.Errorf("correlationID = %v, want %v (the failed message should have been redelivered)", msg.correlationID, correlationID)
			}
		case <-time.After(messageWaitTimeout):
			t.Fatal("timed out waiting for the second consumer to receive the redelivered message")
		}
		waitForCommittedOffset(t, group, topic, 1)
	})

	t.Run("dead-letters a message missing a required header without invoking the handler, and keeps consuming afterwards", func(t *testing.T) {
		topic := uuid.New().String()
		dltTopic := topic + kafka.DLTSuffix
		group := uuid.New().String()
		createTopic(t, topic)
		createTopic(t, dltTopic)

		rawWriter := &kafkagoclient.Writer{
			Addr:     kafkagoclient.TCP(brokerAddr),
			Topic:    topic,
			Balancer: &kafkagoclient.LeastBytes{},
		}
		t.Cleanup(func() { closeQuietly(t, "rawWriter", rawWriter) })

		malformedKey := "incorrect"
		if err := rawWriter.WriteMessages(context.Background(), kafkagoclient.Message{
			Key: []byte(malformedKey),
			Headers: []kafkagoclient.Header{
				{Key: kafka.MessageTypeHeader, Value: []byte(kafka.CreateShipmentCommand.String())},
				{Key: kafka.MessageIDHeader, Value: []byte(uuid.New().String())},
				// correlationId header intentionally omitted.
			},
			Value: []byte(`{}`),
		}); err != nil {
			t.Fatalf("failed to write the message with a missing header: %v", err)
		}

		producer := kafkago.NewKafkaGoMessageProducer([]string{brokerAddr}, topic)
		t.Cleanup(func() { closeQuietly(t, "producer", producer) })
		correctCorrelationID := uuid.New().String()
		sendMessage(t, producer, kafka.CreateShipmentCommand, correctCorrelationID, "correct", map[string]any{"n": 1})

		consumer := kafkago.NewKafkaGoMessageConsumer([]string{brokerAddr}, topic, group)
		t.Cleanup(func() { closeQuietly(t, "consumer", consumer) })
		received := make(chan consumedMessage, 2)
		consumer.ConsumeAsync(func(_ context.Context, mt kafka.MessageType, id uuid.UUID, corr string, p []byte) error {
			received <- consumedMessage{mt, id, corr, p}
			return nil
		})

		select {
		case msg := <-received:
			if msg.correlationID != correctCorrelationID {
				t.Errorf("correlationID = %v, want %v (the malformed message must not reach the handler)", msg.correlationID, correctCorrelationID)
			}
		case <-time.After(messageWaitTimeout):
			t.Fatal("timed out waiting for the well-formed message to be processed")
		}

		select {
		case extra := <-received:
			t.Fatalf("handler was invoked a second time unexpectedly with correlationID %v", extra.correlationID)
		case <-time.After(noExtraMessageWait):
		}

		deadLetter := awaitDeadLetter(t, dltTopic, malformedKey, messageWaitTimeout)
		if gotTopic := string(headerValue(deadLetter.Headers, kafka.DLTOriginalTopicHeader)); gotTopic != topic {
			t.Errorf("original topic = %q, want %q", gotTopic, topic)
		}
		waitForCommittedOffset(t, group, topic, 2)
	})

	t.Run("retries a failing message five times, then dead-letters it with the failure headers and commits the offset", func(t *testing.T) {
		topic := uuid.New().String()
		dltTopic := topic + kafka.DLTSuffix
		group := uuid.New().String()
		createTopic(t, topic)
		createTopic(t, dltTopic)

		producer := kafkago.NewKafkaGoMessageProducer([]string{brokerAddr}, topic)
		t.Cleanup(func() { closeQuietly(t, "producer", producer) })

		key := "1"
		handlerFailure := "simulated handler failure"
		wantCorrelationID := uuid.New().String()
		sendMessage(t, producer, kafka.CreateShipmentCommand, wantCorrelationID, key, map[string]any{"n": 1})

		consumer := kafkago.NewKafkaGoMessageConsumer([]string{brokerAddr}, topic, group)
		t.Cleanup(func() { closeQuietly(t, "consumer", consumer) })
		var handlerCalls atomic.Int32
		consumer.ConsumeAsync(func(_ context.Context, _ kafka.MessageType, _ uuid.UUID, _ string, _ []byte) error {
			handlerCalls.Add(1)
			return errors.New(handlerFailure)
		})

		deadLetter := awaitDeadLetter(t, dltTopic, key, retryMessageWaitTimeout)

		if gotHandlerCalls, wantHandlerCalls := handlerCalls.Load(), int32(maxAttempts); gotHandlerCalls != wantHandlerCalls {
			t.Errorf("handler calls = %d, want %d", gotHandlerCalls, wantHandlerCalls)
		}
		var gotPayload struct {
			N int `json:"n"`
		}
		if err := json.Unmarshal(deadLetter.Value, &gotPayload); err != nil || gotPayload.N != 1 {
			t.Errorf("payload = %q (err %v), want the original {\"n\":1}", deadLetter.Value, err)
		}
		// The business headers survive the trip, so a redrive reuses the same messageId (idempotency)
		// and correlationId (traceability).
		if gotCorrelationID := string(headerValue(deadLetter.Headers, kafka.CorrelationIDHeader)); gotCorrelationID != wantCorrelationID {
			t.Errorf("correlationId = %q, want %q", gotCorrelationID, wantCorrelationID)
		}
		if gotMessageType, wantMessageType := string(headerValue(deadLetter.Headers, kafka.MessageTypeHeader)), kafka.CreateShipmentCommand.String(); gotMessageType != wantMessageType {
			t.Errorf("messageType = %q, want %q", gotMessageType, wantMessageType)
		}
		if gotMessageID := string(headerValue(deadLetter.Headers, kafka.MessageIDHeader)); gotMessageID == "" {
			t.Error("messageId header is missing")
		}
		if gotTopic := string(headerValue(deadLetter.Headers, kafka.DLTOriginalTopicHeader)); gotTopic != topic {
			t.Errorf("original topic = %q, want %q", gotTopic, topic)
		}
		if gotPartition := binary.BigEndian.Uint32(headerValue(deadLetter.Headers, kafka.DLTOriginalPartitionHeader)); gotPartition != 0 {
			t.Errorf("original partition = %d, want 0", gotPartition)
		}
		if gotOffset := binary.BigEndian.Uint64(headerValue(deadLetter.Headers, kafka.DLTOriginalOffsetHeader)); gotOffset != 0 {
			t.Errorf("original offset = %d, want 0", gotOffset)
		}
		if gotGroup := string(headerValue(deadLetter.Headers, kafka.DLTOriginalConsumerGroupHeader)); gotGroup != group {
			t.Errorf("original consumer group = %q, want %q", gotGroup, group)
		}
		if gotMessage := string(headerValue(deadLetter.Headers, kafka.DLTExceptionMessageHeader)); gotMessage != handlerFailure {
			t.Errorf("exception message = %q, want %q", gotMessage, handlerFailure)
		}
		if gotFQCN, wantFQCN := string(headerValue(deadLetter.Headers, kafka.DLTExceptionFQCNHeader)), "*errors.errorString"; gotFQCN != wantFQCN {
			t.Errorf("exception type = %q, want %q", gotFQCN, wantFQCN)
		}
		waitForCommittedOffset(t, group, topic, 1)
	})

	t.Run("dead-letters a non-retryable failure after a single attempt, and keeps consuming afterwards", func(t *testing.T) {
		topic := uuid.New().String()
		dltTopic := topic + kafka.DLTSuffix
		group := uuid.New().String()
		createTopic(t, topic)
		createTopic(t, dltTopic)

		producer := kafkago.NewKafkaGoMessageProducer([]string{brokerAddr}, topic)
		t.Cleanup(func() { closeQuietly(t, "producer", producer) })

		failingKey := "failing"
		failingCorrelationID := uuid.New().String()
		goodCorrelationID := uuid.New().String()
		sendMessage(t, producer, kafka.CreateShipmentCommand, failingCorrelationID, failingKey, map[string]any{"n": 1})
		sendMessage(t, producer, kafka.CreateShipmentCommand, goodCorrelationID, "good", map[string]any{"n": 2})

		consumer := kafkago.NewKafkaGoMessageConsumer([]string{brokerAddr}, topic, group)
		t.Cleanup(func() { closeQuietly(t, "consumer", consumer) })
		var failingCalls atomic.Int32
		received := make(chan string, 2)
		consumer.ConsumeAsync(func(_ context.Context, _ kafka.MessageType, _ uuid.UUID, correlationID string, _ []byte) error {
			if correlationID == failingCorrelationID {
				failingCalls.Add(1)
				return incommon.ErrMalformedMessage.WithCause(errors.New("permanent failure"))
			}
			received <- correlationID
			return nil
		})

		awaitDeadLetter(t, dltTopic, failingKey, nonRetryableDeadLetterTimeout)

		if gotFailingCalls, wantFailingCalls := failingCalls.Load(), int32(1); gotFailingCalls != wantFailingCalls {
			t.Errorf("handler calls for the failing message = %d, want %d", gotFailingCalls, wantFailingCalls)
		}
		select {
		case gotCorrelationID := <-received:
			if gotCorrelationID != goodCorrelationID {
				t.Errorf("correlationID = %v, want %v", gotCorrelationID, goodCorrelationID)
			}
		case <-time.After(messageWaitTimeout):
			t.Fatal("timed out waiting for the message after the dead-lettered one to be processed")
		}
		waitForCommittedOffset(t, group, topic, 2)
	})

	t.Run("does not skip a message while its dead-letter topic is unavailable, and dead-letters it once the topic exists", func(t *testing.T) {
		topic := uuid.New().String()
		dltTopic := topic + kafka.DLTSuffix
		group := uuid.New().String()
		// The dead-letter topic is deliberately not created yet.
		createTopic(t, topic)

		producer := kafkago.NewKafkaGoMessageProducer([]string{brokerAddr}, topic)
		t.Cleanup(func() { closeQuietly(t, "producer", producer) })

		failingKey := "failing"
		failingCorrelationID := uuid.New().String()
		goodCorrelationID := uuid.New().String()
		sendMessage(t, producer, kafka.CreateShipmentCommand, failingCorrelationID, failingKey, map[string]any{"n": 1})
		sendMessage(t, producer, kafka.CreateShipmentCommand, goodCorrelationID, "good", map[string]any{"n": 2})

		consumer := kafkago.NewKafkaGoMessageConsumer([]string{brokerAddr}, topic, group)
		t.Cleanup(func() { closeQuietly(t, "consumer", consumer) })
		failingHandled := make(chan struct{})
		var closeFailingHandled sync.Once
		received := make(chan string, 2)
		consumer.ConsumeAsync(func(_ context.Context, _ kafka.MessageType, _ uuid.UUID, correlationID string, _ []byte) error {
			if correlationID == failingCorrelationID {
				closeFailingHandled.Do(func() { close(failingHandled) })
				return incommon.ErrMalformedMessage.WithCause(errors.New("permanent failure"))
			}
			received <- correlationID
			return nil
		})

		select {
		case <-failingHandled:
		case <-time.After(messageWaitTimeout):
			t.Fatal("timed out waiting for the failing message to be handled")
		}

		// While the dead-letter topic is missing, the failing message must neither be committed nor
		// skipped: the message after it is held back too.
		select {
		case got := <-received:
			t.Fatalf("message %v was processed while the dead-letter topic was unavailable, i.e. the failing message was skipped", got)
		case <-time.After(noExtraMessageWait):
		}
		if got := committedOffset(t, group, topic); got >= 1 {
			t.Errorf("committed offset = %d while the dead-letter topic was unavailable, want none", got)
		}

		createTopic(t, dltTopic)

		awaitDeadLetter(t, dltTopic, failingKey, messageWaitTimeout)
		select {
		case gotCorrelationID := <-received:
			if gotCorrelationID != goodCorrelationID {
				t.Errorf("correlationID = %v, want %v", gotCorrelationID, goodCorrelationID)
			}
		case <-time.After(messageWaitTimeout):
			t.Fatal("timed out waiting for the message after the dead-lettered one to be processed")
		}
		waitForCommittedOffset(t, group, topic, 2)
	})
}

func TestCreateTopics_Integration(t *testing.T) {
	declaredTopics := []string{kafka.ShippingCommandsTopic, kafka.ShippingEventsTopic, kafka.ShippingCommandsDLTTopic}

	t.Run("creates every declared topic, with the dead-letter topic's retention", func(t *testing.T) {
		if err := kafkago.CreateTopics(context.Background(), []string{brokerAddr}); err != nil {
			t.Fatalf("CreateTopics: %v", err)
		}

		client := &kafkagoclient.Client{Addr: kafkagoclient.TCP(brokerAddr)}
		ctx, cancel := context.WithTimeout(context.Background(), brokerCallTimeout)
		defer cancel()

		metadata, err := client.Metadata(ctx, &kafkagoclient.MetadataRequest{Addr: client.Addr, Topics: declaredTopics})
		if err != nil {
			t.Fatalf("failed to fetch topic metadata: %v", err)
		}
		for _, topic := range metadata.Topics {
			if topic.Error != nil {
				t.Errorf("topic %q does not exist: %v", topic.Name, topic.Error)
			}
		}
		if got, want := len(metadata.Topics), len(declaredTopics); got != want {
			t.Errorf("found %d declared topics, want %d", got, want)
		}

		configs, err := client.DescribeConfigs(ctx, &kafkagoclient.DescribeConfigsRequest{
			Addr: client.Addr,
			Resources: []kafkagoclient.DescribeConfigRequestResource{{
				ResourceType: kafkagoclient.ResourceTypeTopic,
				ResourceName: kafka.ShippingCommandsDLTTopic,
				ConfigNames:  []string{"retention.ms"},
			}},
		})
		if err != nil {
			t.Fatalf("failed to describe the dead-letter topic config: %v", err)
		}
		gotRetention := ""
		for _, resource := range configs.Resources {
			for _, entry := range resource.ConfigEntries {
				if entry.ConfigName == "retention.ms" {
					gotRetention = entry.ConfigValue
				}
			}
		}
		if wantRetention := strconv.FormatInt((30 * 24 * time.Hour).Milliseconds(), 10); gotRetention != wantRetention {
			t.Errorf("retention.ms = %q, want %q", gotRetention, wantRetention)
		}
	})

	t.Run("is idempotent: declaring topics that already exist succeeds", func(t *testing.T) {
		if err := kafkago.CreateTopics(context.Background(), []string{brokerAddr}); err != nil {
			t.Fatalf("first CreateTopics: %v", err)
		}

		if err := kafkago.CreateTopics(context.Background(), []string{brokerAddr}); err != nil {
			t.Errorf("second CreateTopics: %v, want nil", err)
		}
	})

	t.Run("fails when the broker is unreachable, instead of letting the service start", func(t *testing.T) {
		ctx, cancel := context.WithTimeout(context.Background(), time.Second)
		defer cancel()

		if err := kafkago.CreateTopics(ctx, []string{"127.0.0.1:1"}); err == nil {
			t.Error("CreateTopics = nil, want an error")
		}
	})
}
