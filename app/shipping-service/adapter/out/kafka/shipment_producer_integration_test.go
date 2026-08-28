//go:build integration

package kafka_test

import (
	"context"
	"encoding/json"
	"io"
	"log"
	"os"
	"testing"
	"time"

	commonkafka "shippingservice/adapter/common/kafka"
	shipmentkafka "shippingservice/adapter/out/kafka"
	"shippingservice/adapter/out/kafka/dto"
	kafkautil "shippingservice/application/port/common/kafka"
	"shippingservice/domain/model"
	"shippingservice/domain/value"
	"shippingservice/infrastructure/kafkago"

	"github.com/google/uuid"
	kafkagoclient "github.com/segmentio/kafka-go"
	"github.com/testcontainers/testcontainers-go/modules/redpanda"
)

const (
	redpandaImage = "docker.redpanda.com/redpandadata/redpanda:v26.1.16"

	// messageWaitTimeout bounds how long a subtest waits for the published
	// message to become readable before failing.
	messageWaitTimeout = 20 * time.Second

	// brokerCallTimeout bounds how long topic creation waits for the
	// broker to confirm the topic exists.
	brokerCallTimeout = 10 * time.Second
)

var brokerAddr string

// TestMain boots a single Redpanda broker shared by every test in this file.
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

// createTopic pre-creates a single-partition topic.
//
// It goes through kafka-go's default client/transport (the same shared
// connection pool NewKafkaGoMessageProducer uses) rather than a plain Conn,
// and waits for the response here: that pool caches cluster metadata and
// only refreshes it as a side effect of a CreateTopics call observed
// through the pool itself, blocking until the new topic is visible. A topic
// created via a bare Conn bypasses that pool entirely, leaving its cache
// stale and producing to the new topic failing with "Unknown Topic Or
// Partition" even though the topic exists.
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

func headerValue(headers []kafkagoclient.Header, key string) string {
	for _, h := range headers {
		if h.Key == key {
			return string(h.Value)
		}
	}
	return ""
}

func testShipment(t *testing.T, orderID uint64, trackingNumber string, status model.ShipmentStatus) *model.Shipment {
	t.Helper()
	address, err := value.NewAddress("Kyiv", "01001", "Khreshchatyk 1", "")
	if err != nil {
		t.Fatalf("failed to build test address: %v", err)
	}
	now := time.Now()
	shipment, err := model.NewExistingShipment(1, orderID, trackingNumber, address, status, model.HolderCourier, now, now)
	if err != nil {
		t.Fatalf("failed to build test shipment: %v", err)
	}
	return shipment
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

// TestShipmentKafkaProducer_Integration verifies the actual wire contract
// this producer publishes - topic, headers and payload shape - since that's
// exactly what an independent consumer (e.g. the Order Service, reading
// shipping.events) relies on, and no mock-based unit test can prove real
// JSON encoding or header wiring is correct end to end.
func TestShipmentKafkaProducer_Integration(t *testing.T) {
	t.Run("publishes a shipment status changed event with the correct type, key and payload", func(t *testing.T) {
		topic := uuid.New().String()
		createTopic(t, topic)

		messageProducer := kafkago.NewKafkaGoMessageProducer([]string{brokerAddr}, topic)
		producer := shipmentkafka.NewShipmentKafkaProducer(messageProducer)
		t.Cleanup(func() { closeQuietly(t, "producer", producer) })

		correlationID := uuid.New().String()
		orderID := uint64(42)
		shipment := testShipment(t, orderID, "TRACK-1", model.Delivered)

		if err := producer.PublishShipmentStatusChanged(context.Background(), correlationID, shipment); err != nil {
			t.Fatalf("failed to publish: %v", err)
		}

		reader := kafkagoclient.NewReader(kafkagoclient.ReaderConfig{Brokers: []string{brokerAddr}, Topic: topic, GroupID: uuid.New().String()})
		t.Cleanup(func() { closeQuietly(t, "reader", reader) })
		ctx, cancel := context.WithTimeout(context.Background(), messageWaitTimeout)
		defer cancel()
		msg, err := reader.FetchMessage(ctx)
		if err != nil {
			t.Fatalf("failed to read the published message: %v", err)
		}

		wantMessageType := commonkafka.ShipmentDeliveredEvent
		if gotMessageType := headerValue(msg.Headers, commonkafka.MessageTypeHeader); gotMessageType != wantMessageType.String() {
			t.Errorf("messageType header = %q, want %q", gotMessageType, wantMessageType)
		}
		if gotCorrelationID := headerValue(msg.Headers, commonkafka.CorrelationIDHeader); gotCorrelationID != correlationID {
			t.Errorf("correlationId header = %q, want %q", gotCorrelationID, correlationID)
		}
		wantKey := kafkautil.FormatKafkaId(orderID)
		if string(msg.Key) != wantKey {
			t.Errorf("key = %q, want %q", msg.Key, wantKey)
		}
		var payload dto.ShipmentStatusChangedEvent
		if err = json.Unmarshal(msg.Value, &payload); err != nil {
			t.Fatalf("failed to unmarshal payload: %v", err)
		}
		wantPayload := dto.ToShipmentStatusChangedEvent(shipment)
		if payload != wantPayload {
			t.Errorf("payload = %+v, want %+v", payload, wantPayload)
		}
	})
}
