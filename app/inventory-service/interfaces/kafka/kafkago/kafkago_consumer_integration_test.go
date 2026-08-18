//go:build integration

package kafkago_test

import (
	"context"
	"encoding/json"
	"errors"
	"io"
	"log"
	"os"
	"strconv"
	"testing"
	"time"

	"inventoryservice/application/port/common/kafka"
	producerkafkago "inventoryservice/infrastructure/kafkago"
	"inventoryservice/interfaces/kafka/kafkago"

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

// closeQuietly closes c. If that fails, it just logs the error instead of
// failing the test - a cleanup error shouldn't turn a passing test into a
// failing one.
func closeQuietly(t *testing.T, name string, c io.Closer) {
	t.Helper()
	if err := c.Close(); err != nil {
		t.Logf("failed to close %s: %v", name, err)
	}
}

type consumedMessage struct {
	messageType   kafka.MessageType
	messageID     string
	correlationID string
	payload       []byte
}

func TestKafkaGoMessageConsumer_Integration(t *testing.T) {
	t.Run("delivers the produced message with correct headers and payload", func(t *testing.T) {
		topic := uuid.New().String()
		group := uuid.New().String()
		createTopic(t, topic)

		producer := producerkafkago.NewKafkaGoMessageProducer([]string{brokerAddr}, topic)
		t.Cleanup(func() { closeQuietly(t, "producer", producer) })

		type payload struct {
			OrderID uint64 `json:"orderId"`
		}
		wantMessageType := kafka.CancelReservationCommand
		wantCorrelationID := uuid.New().String()
		orderId := uint64(42)
		wantPayload := payload{OrderID: orderId}

		if err := producer.Send(wantMessageType, wantCorrelationID, strconv.FormatUint(orderId, 10), wantPayload); err != nil {
			t.Fatalf("failed to send message: %v", err)
		}

		consumer := kafkago.NewKafkaGoMessageConsumer([]string{brokerAddr}, topic, group)
		t.Cleanup(func() { closeQuietly(t, "consumer", consumer) })
		received := make(chan consumedMessage, 1)
		consumer.ConsumeAsync(func(mt kafka.MessageType, id, corr string, p []byte) error {
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
			if _, err := uuid.Parse(msg.messageID); err != nil {
				t.Errorf("messageID = %q is not a valid uuid: %v", msg.messageID, err)
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

		producer := producerkafkago.NewKafkaGoMessageProducer([]string{brokerAddr}, topic)
		t.Cleanup(func() { closeQuietly(t, "producer", producer) })

		correlationID1 := uuid.New().String()
		correlationID2 := uuid.New().String()
		if err := producer.Send(kafka.CancelReservationCommand, correlationID1, "1", map[string]any{"n": 1}); err != nil {
			t.Fatalf("failed to send the first message: %v", err)
		}
		if err := producer.Send(kafka.CancelReservationCommand, correlationID2, "2", map[string]any{"n": 2}); err != nil {
			t.Fatalf("failed to send the second message: %v", err)
		}

		consumer := kafkago.NewKafkaGoMessageConsumer([]string{brokerAddr}, topic, group)
		t.Cleanup(func() { closeQuietly(t, "consumer", consumer) })
		received := make(chan consumedMessage, 2)
		consumer.ConsumeAsync(func(mt kafka.MessageType, id, corr string, p []byte) error {
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

		producer := producerkafkago.NewKafkaGoMessageProducer([]string{brokerAddr}, topic)
		t.Cleanup(func() { closeQuietly(t, "producer", producer) })

		// The second message is only sent after the first consumer has
		// closed, so consumer1 can only ever see the first message - if
		// both were sent upfront, consumer1's background loop could fetch
		// and commit the second one too before the test gets a chance to
		// close it, making the "advances by exactly one" assertion racy.
		correlationID1 := uuid.New().String()
		if err := producer.Send(kafka.CancelReservationCommand, correlationID1, "1", map[string]any{"n": 1}); err != nil {
			t.Fatalf("failed to send the first message: %v", err)
		}

		consumer1 := kafkago.NewKafkaGoMessageConsumer([]string{brokerAddr}, topic, group)
		t.Cleanup(func() { closeQuietly(t, "consumer1", consumer1) })
		processed1 := make(chan struct{})
		consumer1.ConsumeAsync(func(_ kafka.MessageType, _ string, _ string, _ []byte) error {
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
		if err := producer.Send(kafka.CancelReservationCommand, correlationID2, "2", map[string]any{"n": 2}); err != nil {
			t.Fatalf("failed to send the second message: %v", err)
		}

		consumer2 := kafkago.NewKafkaGoMessageConsumer([]string{brokerAddr}, topic, group)
		t.Cleanup(func() { closeQuietly(t, "consumer2", consumer2) })
		received2 := make(chan consumedMessage, 1)
		consumer2.ConsumeAsync(func(mt kafka.MessageType, id, corr string, p []byte) error {
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

	t.Run("does not commit the offset when handling fails, so the message is redelivered", func(t *testing.T) {
		topic := uuid.New().String()
		group := uuid.New().String()
		createTopic(t, topic)

		producer := producerkafkago.NewKafkaGoMessageProducer([]string{brokerAddr}, topic)
		t.Cleanup(func() { closeQuietly(t, "producer", producer) })

		correlationID := uuid.New().String()
		if err := producer.Send(kafka.CancelReservationCommand, correlationID, "1", map[string]any{"n": 1}); err != nil {
			t.Fatalf("failed to send message: %v", err)
		}

		consumer1 := kafkago.NewKafkaGoMessageConsumer([]string{brokerAddr}, topic, group)
		t.Cleanup(func() { closeQuietly(t, "consumer1", consumer1) })
		received1 := make(chan struct{})
		consumer1.ConsumeAsync(func(_ kafka.MessageType, _ string, _ string, _ []byte) error {
			close(received1)
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
		consumer2.ConsumeAsync(func(mt kafka.MessageType, id, corr string, p []byte) error {
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

	t.Run("skips a message missing a required header without invoking the handler, and keeps consuming afterwards", func(t *testing.T) {
		topic := uuid.New().String()
		group := uuid.New().String()
		createTopic(t, topic)

		rawWriter := &kafkagoclient.Writer{
			Addr:     kafkagoclient.TCP(brokerAddr),
			Topic:    topic,
			Balancer: &kafkagoclient.LeastBytes{},
		}
		t.Cleanup(func() { closeQuietly(t, "rawWriter", rawWriter) })

		if err := rawWriter.WriteMessages(context.Background(), kafkagoclient.Message{
			Key: []byte("incorrect"),
			Headers: []kafkagoclient.Header{
				{Key: kafka.MessageTypeHeader, Value: []byte(kafka.CancelReservationCommand.String())},
				{Key: kafka.MessageIDHeader, Value: []byte(uuid.New().String())},
				// correlationId header intentionally omitted.
			},
			Value: []byte(`{}`),
		}); err != nil {
			t.Fatalf("failed to write the message with a missing header: %v", err)
		}

		producer := producerkafkago.NewKafkaGoMessageProducer([]string{brokerAddr}, topic)
		t.Cleanup(func() { closeQuietly(t, "producer", producer) })
		correctCorrelationID := uuid.New().String()
		if err := producer.Send(kafka.CancelReservationCommand, correctCorrelationID, "correct", map[string]any{"n": 1}); err != nil {
			t.Fatalf("failed to send the well-formed message: %v", err)
		}

		consumer := kafkago.NewKafkaGoMessageConsumer([]string{brokerAddr}, topic, group)
		t.Cleanup(func() { closeQuietly(t, "consumer", consumer) })
		received := make(chan consumedMessage, 2)
		consumer.ConsumeAsync(func(mt kafka.MessageType, id, corr string, p []byte) error {
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
	})
}
