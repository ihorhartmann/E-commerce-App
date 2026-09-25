package kafka

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"testing"

	commonkafka "shippingservice/adapter/common/kafka"
	"shippingservice/adapter/in/kafka/dto"
	servicemocks "shippingservice/application/port/in/mocks"
	idempotencydto "shippingservice/application/port/out/idempotency/dto"
	"shippingservice/domain"
	"shippingservice/domain/value"

	"go.uber.org/mock/gomock"
)

func testAddressDTO() dto.AddressDTO {
	return dto.AddressDTO{City: "Kyiv", PostalCode: "01001", Line1: "Khreshchatyk 1", Line2: ""}
}

func TestShipmentKafkaConsumer_handleMessage(t *testing.T) {
	t.Run("dispatches CreateShipmentCommand to the shipment service", func(t *testing.T) {
		orderID := uint64(42)
		command := dto.CreateShipmentCommand{OrderID: orderID, Address: testAddressDTO()}
		payload, err := json.Marshal(command)
		if err != nil {
			t.Fatalf("failed to marshal command: %v", err)
		}
		wantAddress, err := value.NewAddress(command.Address.City, command.Address.PostalCode, command.Address.Line1, command.Address.Line2)
		if err != nil {
			t.Fatalf("failed to build expected address: %v", err)
		}
		msg := idempotencydto.KafkaMessageMetadata{MessageID: "msg-1", CorrelationID: "corr-1", MessageType: string(commonkafka.CreateShipmentCommand)}

		shipmentService := servicemocks.NewMockShipmentService(gomock.NewController(t))
		shipmentService.EXPECT().
			CreateShipment(gomock.Any(), msg, orderID, wantAddress).
			Return(nil, nil).
			Times(1)
		consumer := NewShipmentKafkaConsumer(nil, shipmentService)

		if err = consumer.handleMessage(context.Background(), commonkafka.CreateShipmentCommand, msg.MessageID, msg.CorrelationID, payload); err != nil {
			t.Errorf("err = %v, want nil", err)
		}
	})

	t.Run("returns a transient service error unmarked, so it gets retried", func(t *testing.T) {
		orderID := uint64(7)
		command := dto.CreateShipmentCommand{OrderID: orderID, Address: testAddressDTO()}
		payload, err := json.Marshal(command)
		if err != nil {
			t.Fatalf("failed to marshal command: %v", err)
		}
		wantAddress, err := value.NewAddress(command.Address.City, command.Address.PostalCode, command.Address.Line1, command.Address.Line2)
		if err != nil {
			t.Fatalf("failed to build expected address: %v", err)
		}
		msg := idempotencydto.KafkaMessageMetadata{MessageID: "msg-2", CorrelationID: "corr-2", MessageType: string(commonkafka.CreateShipmentCommand)}
		serviceErr := errors.New("shipment creation failed")

		shipmentService := servicemocks.NewMockShipmentService(gomock.NewController(t))
		shipmentService.EXPECT().
			CreateShipment(gomock.Any(), msg, orderID, wantAddress).
			Return(nil, serviceErr).
			Times(1)
		consumer := NewShipmentKafkaConsumer(nil, shipmentService)

		err = consumer.handleMessage(context.Background(), commonkafka.CreateShipmentCommand, msg.MessageID, msg.CorrelationID, payload)

		if !errors.Is(err, serviceErr) {
			t.Errorf("err = %v, want %v", err, serviceErr)
		}
		if errors.Is(err, ErrNonRetryable) {
			t.Errorf("err = %v, want a retryable error", err)
		}
	})

	t.Run("marks a service error caused by invalid input as non-retryable", func(t *testing.T) {
		orderID := uint64(8)
		command := dto.CreateShipmentCommand{OrderID: orderID, Address: testAddressDTO()}
		payload, err := json.Marshal(command)
		if err != nil {
			t.Fatalf("failed to marshal command: %v", err)
		}
		wantAddress, err := value.NewAddress(command.Address.City, command.Address.PostalCode, command.Address.Line1, command.Address.Line2)
		if err != nil {
			t.Fatalf("failed to build expected address: %v", err)
		}
		msg := idempotencydto.KafkaMessageMetadata{MessageID: "msg-6", CorrelationID: "corr-6", MessageType: string(commonkafka.CreateShipmentCommand)}
		serviceErr := fmt.Errorf("create shipment: %w", domain.ErrInvalidInput)

		shipmentService := servicemocks.NewMockShipmentService(gomock.NewController(t))
		shipmentService.EXPECT().
			CreateShipment(gomock.Any(), msg, orderID, wantAddress).
			Return(nil, serviceErr).
			Times(1)
		consumer := NewShipmentKafkaConsumer(nil, shipmentService)

		err = consumer.handleMessage(context.Background(), commonkafka.CreateShipmentCommand, msg.MessageID, msg.CorrelationID, payload)

		if !errors.Is(err, ErrNonRetryable) {
			t.Errorf("err = %v, want a non-retryable error", err)
		}
		if !errors.Is(err, domain.ErrInvalidInput) {
			t.Errorf("err = %v, want it to wrap %v", err, domain.ErrInvalidInput)
		}
	})

	t.Run("returns a non-retryable error without calling the service when the payload is malformed JSON", func(t *testing.T) {
		shipmentService := servicemocks.NewMockShipmentService(gomock.NewController(t))
		shipmentService.EXPECT().CreateShipment(gomock.Any(), gomock.Any(), gomock.Any(), gomock.Any()).Times(0)
		consumer := NewShipmentKafkaConsumer(nil, shipmentService)

		if err := consumer.handleMessage(context.Background(), commonkafka.CreateShipmentCommand, "msg-3", "corr-3", []byte("not json")); !errors.Is(err, ErrNonRetryable) {
			t.Errorf("err = %v, want a non-retryable unmarshal error", err)
		}
	})

	t.Run("returns a non-retryable error without calling the service when the address is invalid", func(t *testing.T) {
		command := dto.CreateShipmentCommand{OrderID: 1, Address: dto.AddressDTO{City: "", PostalCode: "01001", Line1: "Khreshchatyk 1"}}
		payload, err := json.Marshal(command)
		if err != nil {
			t.Fatalf("failed to marshal command: %v", err)
		}

		shipmentService := servicemocks.NewMockShipmentService(gomock.NewController(t))
		shipmentService.EXPECT().CreateShipment(gomock.Any(), gomock.Any(), gomock.Any(), gomock.Any()).Times(0)
		consumer := NewShipmentKafkaConsumer(nil, shipmentService)

		err = consumer.handleMessage(context.Background(), commonkafka.CreateShipmentCommand, "msg-4", "corr-4", payload)

		if !errors.Is(err, domain.ErrInvalidInput) {
			t.Errorf("err = %v, want %v", err, domain.ErrInvalidInput)
		}
		if !errors.Is(err, ErrNonRetryable) {
			t.Errorf("err = %v, want a non-retryable error", err)
		}
	})

	t.Run("ignores unrecognized message types without calling the service", func(t *testing.T) {
		shipmentService := servicemocks.NewMockShipmentService(gomock.NewController(t))
		shipmentService.EXPECT().CreateShipment(gomock.Any(), gomock.Any(), gomock.Any(), gomock.Any()).Times(0)
		consumer := NewShipmentKafkaConsumer(nil, shipmentService)

		if err := consumer.handleMessage(context.Background(), "SomeUnrelatedEvent", "msg-5", "corr-5", []byte(`{}`)); err != nil {
			t.Errorf("err = %v, want nil", err)
		}
	})
}
