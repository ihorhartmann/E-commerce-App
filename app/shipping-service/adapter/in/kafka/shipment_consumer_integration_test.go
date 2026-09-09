//go:build integration

package kafka_test

import (
	"context"
	"io"
	"log"
	"os"
	"testing"
	"time"

	commonkafka "shippingservice/adapter/common/kafka"
	shipmentkafka "shippingservice/adapter/in/kafka"
	"shippingservice/adapter/in/kafka/dto"
	servicemocks "shippingservice/application/port/in/mocks"
	idempotencydto "shippingservice/application/port/out/idempotency/dto"
	"shippingservice/domain/model"
	"shippingservice/domain/value"
	"shippingservice/infrastructure/kafkago"

	"github.com/google/uuid"
	kafkagoclient "github.com/segmentio/kafka-go"
	"github.com/testcontainers/testcontainers-go/modules/redpanda"
	"go.uber.org/mock/gomock"
)

const (
	redpandaImage = "docker.redpanda.com/redpandadata/redpanda:v26.1.16"

	// messageWaitTimeout bounds how long a subtest waits for an expected
	// message or event before failing.
	messageWaitTimeout = 20 * time.Second

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

func TestShipmentKafkaConsumer_Integration(t *testing.T) {
	t.Run("dispatches a CreateShipmentCommand message to the shipment service", func(t *testing.T) {
		topic := uuid.New().String()
		group := uuid.New().String()
		createTopic(t, topic)

		producer := kafkago.NewKafkaGoMessageProducer([]string{brokerAddr}, topic)
		t.Cleanup(func() { closeQuietly(t, "producer", producer) })

		correlationID := uuid.New().String()
		orderID := uint64(42)
		command := dto.CreateShipmentCommand{
			OrderID: orderID,
			Address: dto.AddressDTO{City: "Kyiv", PostalCode: "01001", Line1: "Khreshchatyk 1"},
		}
		if err := producer.Send(commonkafka.CreateShipmentCommand, correlationID, "42", command); err != nil {
			t.Fatalf("failed to send message: %v", err)
		}

		wantAddress, err := value.NewAddress(command.Address.City, command.Address.PostalCode, command.Address.Line1, command.Address.Line2)
		if err != nil {
			t.Fatalf("failed to build expected address: %v", err)
		}

		shipmentService := servicemocks.NewMockShipmentService(gomock.NewController(t))
		done := make(chan struct{})
		shipmentService.EXPECT().
			CreateShipment(gomock.Any(), gomock.Any(), orderID, wantAddress).
			DoAndReturn(func(context.Context, idempotencydto.KafkaMessageMetadata, uint64, value.Address) (*model.Shipment, error) {
				close(done)
				return nil, nil
			}).
			Times(1)

		consumer := kafkago.NewKafkaGoMessageConsumer([]string{brokerAddr}, topic, group)
		shipmentConsumer := shipmentkafka.NewShipmentKafkaConsumer(consumer, shipmentService)
		shipmentConsumer.Start()
		t.Cleanup(func() { closeQuietly(t, "shipmentConsumer", shipmentConsumer) })

		select {
		case <-done:
		case <-time.After(messageWaitTimeout):
			t.Fatal("timed out waiting for the shipment service to be invoked")
		}
		// The service is invoked before the background commit actually
		// happens - wait for the broker to confirm the commit landed
		// before Cleanup closes the consumer.
		waitForCommittedOffset(t, group, topic, 1)
	})

	t.Run("ignores unrecognized message types and keeps dispatching subsequent messages", func(t *testing.T) {
		topic := uuid.New().String()
		group := uuid.New().String()
		createTopic(t, topic)

		producer := kafkago.NewKafkaGoMessageProducer([]string{brokerAddr}, topic)
		t.Cleanup(func() { closeQuietly(t, "producer", producer) })

		if err := producer.Send("SomeUnrelatedEvent", uuid.New().String(), "1", map[string]any{"noop": true}); err != nil {
			t.Fatalf("failed to send the unrecognized message: %v", err)
		}

		correlationID := uuid.New().String()
		orderID := uint64(7)
		command := dto.CreateShipmentCommand{
			OrderID: orderID,
			Address: dto.AddressDTO{City: "Lviv", PostalCode: "79000", Line1: "Rynok 1"},
		}
		if err := producer.Send(commonkafka.CreateShipmentCommand, correlationID, "7", command); err != nil {
			t.Fatalf("failed to send the create shipment message: %v", err)
		}

		wantAddress, err := value.NewAddress(command.Address.City, command.Address.PostalCode, command.Address.Line1, command.Address.Line2)
		if err != nil {
			t.Fatalf("failed to build expected address: %v", err)
		}

		shipmentService := servicemocks.NewMockShipmentService(gomock.NewController(t))
		done := make(chan struct{})
		shipmentService.EXPECT().
			CreateShipment(gomock.Any(), gomock.Any(), orderID, wantAddress).
			DoAndReturn(func(context.Context, idempotencydto.KafkaMessageMetadata, uint64, value.Address) (*model.Shipment, error) {
				close(done)
				return nil, nil
			}).
			Times(1)

		consumer := kafkago.NewKafkaGoMessageConsumer([]string{brokerAddr}, topic, group)
		shipmentConsumer := shipmentkafka.NewShipmentKafkaConsumer(consumer, shipmentService)
		shipmentConsumer.Start()
		t.Cleanup(func() { closeQuietly(t, "shipmentConsumer", shipmentConsumer) })

		select {
		case <-done:
		case <-time.After(messageWaitTimeout):
			t.Fatal("timed out waiting for the shipment service to be invoked for the recognized message")
		}
		// Both the unrecognized and the recognized message get committed
		// (the first is ignored, not rejected) - wait for both commits to
		// land before Cleanup closes the consumer.
		waitForCommittedOffset(t, group, topic, 2)
	})
}
