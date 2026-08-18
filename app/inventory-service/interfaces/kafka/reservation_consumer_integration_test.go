//go:build integration

package kafka_test

import (
	"context"
	"io"
	"log"
	"os"
	"testing"
	"time"

	"inventoryservice/application/dto"
	commonkafka "inventoryservice/application/port/common/kafka"
	"inventoryservice/application/port/in/mocks"
	producerkafkago "inventoryservice/infrastructure/kafkago"
	inventorykafka "inventoryservice/interfaces/kafka"
	kafkadto "inventoryservice/interfaces/kafka/dto"
	"inventoryservice/interfaces/kafka/kafkago"

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

func TestReservationKafkaConsumer_Integration(t *testing.T) {
	t.Run("dispatches a CancelReservationCommand message to the reservation service", func(t *testing.T) {
		topic := uuid.New().String()
		group := uuid.New().String()
		createTopic(t, topic)

		producer := producerkafkago.NewKafkaGoMessageProducer([]string{brokerAddr}, topic)
		t.Cleanup(func() { closeQuietly(t, "producer", producer) })

		correlationID := uuid.New().String()
		orderID := uint64(42)
		if err := producer.Send(commonkafka.CancelReservationCommand, correlationID, "42", kafkadto.CancelReservationCommand{OrderID: orderID}); err != nil {
			t.Fatalf("failed to send message: %v", err)
		}

		reservationService := mocks.NewMockReservationService(gomock.NewController(t))
		done := make(chan struct{})
		reservationService.EXPECT().
			Cancel(gomock.Any(), orderID).
			DoAndReturn(func(context.Context, uint64) error {
				close(done)
				return nil
			}).
			Times(1)
		productService := mocks.NewMockProductService(gomock.NewController(t))

		consumer := kafkago.NewKafkaGoMessageConsumer([]string{brokerAddr}, topic, group)
		reservationConsumer := inventorykafka.NewReservationKafkaConsumer(consumer, reservationService, productService)
		reservationConsumer.Start()
		t.Cleanup(func() { closeQuietly(t, "reservationConsumer", reservationConsumer) })

		select {
		case <-done:
		case <-time.After(messageWaitTimeout):
			t.Fatal("timed out waiting for the reservation service to be invoked")
		}
		waitForCommittedOffset(t, group, topic, 1)
	})

	t.Run("dispatches a RestockCommand message to the product service with the mapped items", func(t *testing.T) {
		topic := uuid.New().String()
		group := uuid.New().String()
		createTopic(t, topic)

		producer := producerkafkago.NewKafkaGoMessageProducer([]string{brokerAddr}, topic)
		t.Cleanup(func() { closeQuietly(t, "producer", producer) })

		correlationID := uuid.New().String()
		orderID := uint64(7)
		items := []kafkadto.RestockItem{{ProductID: 1, Quantity: 5}}
		if err := producer.Send(commonkafka.RestockCommand, correlationID, "7", kafkadto.RestockCommand{OrderID: orderID, Items: items}); err != nil {
			t.Fatalf("failed to send message: %v", err)
		}
		wantReservationDTOs := []dto.ReservationDTO{{ProductID: 1, Quantity: 5}}

		reservationService := mocks.NewMockReservationService(gomock.NewController(t))
		productService := mocks.NewMockProductService(gomock.NewController(t))
		done := make(chan struct{})
		productService.EXPECT().
			Restock(gomock.Any(), wantReservationDTOs).
			DoAndReturn(func(context.Context, []dto.ReservationDTO) error {
				close(done)
				return nil
			}).
			Times(1)

		consumer := kafkago.NewKafkaGoMessageConsumer([]string{brokerAddr}, topic, group)
		reservationConsumer := inventorykafka.NewReservationKafkaConsumer(consumer, reservationService, productService)
		reservationConsumer.Start()
		t.Cleanup(func() { closeQuietly(t, "reservationConsumer", reservationConsumer) })

		select {
		case <-done:
		case <-time.After(messageWaitTimeout):
			t.Fatal("timed out waiting for the product service to be invoked")
		}
		waitForCommittedOffset(t, group, topic, 1)
	})

	t.Run("ignores unrecognized message types and keeps dispatching subsequent messages", func(t *testing.T) {
		topic := uuid.New().String()
		group := uuid.New().String()
		createTopic(t, topic)

		producer := producerkafkago.NewKafkaGoMessageProducer([]string{brokerAddr}, topic)
		t.Cleanup(func() { closeQuietly(t, "producer", producer) })

		if err := producer.Send("SomeUnrelatedEvent", uuid.New().String(), "1", map[string]any{"noop": true}); err != nil {
			t.Fatalf("failed to send the unrecognized message: %v", err)
		}

		correlationID := uuid.New().String()
		orderID := uint64(13)
		if err := producer.Send(commonkafka.CancelReservationCommand, correlationID, "13", kafkadto.CancelReservationCommand{OrderID: orderID}); err != nil {
			t.Fatalf("failed to send the cancel reservation message: %v", err)
		}

		reservationService := mocks.NewMockReservationService(gomock.NewController(t))
		done := make(chan struct{})
		reservationService.EXPECT().
			Cancel(gomock.Any(), orderID).
			DoAndReturn(func(context.Context, uint64) error {
				close(done)
				return nil
			}).
			Times(1)
		productService := mocks.NewMockProductService(gomock.NewController(t))

		consumer := kafkago.NewKafkaGoMessageConsumer([]string{brokerAddr}, topic, group)
		reservationConsumer := inventorykafka.NewReservationKafkaConsumer(consumer, reservationService, productService)
		reservationConsumer.Start()
		t.Cleanup(func() { closeQuietly(t, "reservationConsumer", reservationConsumer) })

		select {
		case <-done:
		case <-time.After(messageWaitTimeout):
			t.Fatal("timed out waiting for the reservation service to be invoked for the recognized message")
		}
		waitForCommittedOffset(t, group, topic, 2)
	})
}
