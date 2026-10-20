package kafka

import (
	"context"
	"encoding/json"
	"errors"
	"testing"

	commonkafka "shippingservice/adapter/common/kafka"
	"shippingservice/adapter/in/kafka/dto"
	servicemocks "shippingservice/application/port/in/mocks"
	idempotencydto "shippingservice/application/port/out/idempotency/dto"
	"shippingservice/application/port/out/repository"
	"shippingservice/domain/value"

	"go.uber.org/mock/gomock"

	"github.com/google/uuid"
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
		msg := idempotencydto.KafkaMessageMetadata{MessageID: uuid.New(), CorrelationID: "corr-1", MessageType: string(commonkafka.CreateShipmentCommand)}

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

	t.Run("returns the service's error as it is - the retry loop decides by its category", func(t *testing.T) {
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
		msg := idempotencydto.KafkaMessageMetadata{MessageID: uuid.New(), CorrelationID: "corr-2", MessageType: string(commonkafka.CreateShipmentCommand)}
		serviceErr := repository.ErrShipmentAlreadyExists.WithInternalMessage("order already has a shipment")

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
	})

	t.Run("returns ErrMalformedMessage without calling the service when the payload is malformed JSON", func(t *testing.T) {
		shipmentService := servicemocks.NewMockShipmentService(gomock.NewController(t))
		shipmentService.EXPECT().CreateShipment(gomock.Any(), gomock.Any(), gomock.Any(), gomock.Any()).Times(0)
		consumer := NewShipmentKafkaConsumer(nil, shipmentService)

		if err := consumer.handleMessage(context.Background(), commonkafka.CreateShipmentCommand, uuid.New(), "corr-3", []byte("not json")); !errors.Is(err, ErrMalformedMessage) {
			t.Errorf("err = %v, want %v", err, ErrMalformedMessage)
		}
	})

	t.Run("returns ErrInvalidAddress without calling the service when the address is invalid", func(t *testing.T) {
		command := dto.CreateShipmentCommand{OrderID: 1, Address: dto.AddressDTO{City: "", PostalCode: "01001", Line1: "Khreshchatyk 1"}}
		payload, err := json.Marshal(command)
		if err != nil {
			t.Fatalf("failed to marshal command: %v", err)
		}

		shipmentService := servicemocks.NewMockShipmentService(gomock.NewController(t))
		shipmentService.EXPECT().CreateShipment(gomock.Any(), gomock.Any(), gomock.Any(), gomock.Any()).Times(0)
		consumer := NewShipmentKafkaConsumer(nil, shipmentService)

		err = consumer.handleMessage(context.Background(), commonkafka.CreateShipmentCommand, uuid.New(), "corr-4", payload)

		if !errors.Is(err, value.ErrInvalidAddress) {
			t.Errorf("err = %v, want %v", err, value.ErrInvalidAddress)
		}
	})

	t.Run("dispatches CancelShipmentCommand to the shipment service", func(t *testing.T) {
		orderID := uint64(42)
		msg := idempotencydto.KafkaMessageMetadata{MessageID: uuid.New(), CorrelationID: "corr-8", MessageType: string(commonkafka.CancelShipmentCommand)}

		shipmentService := servicemocks.NewMockShipmentService(gomock.NewController(t))
		shipmentService.EXPECT().CancelByOrder(gomock.Any(), msg, orderID).Return(true, nil).Times(1)
		consumer := NewShipmentKafkaConsumer(nil, shipmentService)

		err := consumer.handleMessage(context.Background(), commonkafka.CancelShipmentCommand, msg.MessageID, msg.CorrelationID, []byte(`{"orderId":42}`))

		if err != nil {
			t.Errorf("err = %v, want nil", err)
		}
	})

	t.Run("handles a CancelShipmentCommand that found nothing left to cancel without an error", func(t *testing.T) {
		orderID := uint64(43)
		msg := idempotencydto.KafkaMessageMetadata{MessageID: uuid.New(), CorrelationID: "corr-9", MessageType: string(commonkafka.CancelShipmentCommand)}

		shipmentService := servicemocks.NewMockShipmentService(gomock.NewController(t))
		shipmentService.EXPECT().CancelByOrder(gomock.Any(), msg, orderID).Return(false, nil).Times(1)
		consumer := NewShipmentKafkaConsumer(nil, shipmentService)

		err := consumer.handleMessage(context.Background(), commonkafka.CancelShipmentCommand, msg.MessageID, msg.CorrelationID, []byte(`{"orderId":43}`))

		if err != nil {
			t.Errorf("err = %v, want nil", err)
		}
	})

	t.Run("returns the CancelShipmentCommand service error as it is - the retry loop decides by its category", func(t *testing.T) {
		orderID := uint64(45)
		msg := idempotencydto.KafkaMessageMetadata{MessageID: uuid.New(), CorrelationID: "corr-11", MessageType: string(commonkafka.CancelShipmentCommand)}

		shipmentService := servicemocks.NewMockShipmentService(gomock.NewController(t))
		shipmentService.EXPECT().CancelByOrder(gomock.Any(), msg, orderID).Return(false, repository.ErrConcurrentUpdate).Times(1)
		consumer := NewShipmentKafkaConsumer(nil, shipmentService)

		err := consumer.handleMessage(context.Background(), commonkafka.CancelShipmentCommand, msg.MessageID, msg.CorrelationID, []byte(`{"orderId":45}`))

		if !errors.Is(err, repository.ErrConcurrentUpdate) {
			t.Errorf("err = %v, want %v", err, repository.ErrConcurrentUpdate)
		}
	})

	t.Run("returns ErrMalformedMessage without calling the service when the CancelShipmentCommand payload is malformed JSON", func(t *testing.T) {
		shipmentService := servicemocks.NewMockShipmentService(gomock.NewController(t))
		shipmentService.EXPECT().CancelByOrder(gomock.Any(), gomock.Any(), gomock.Any()).Times(0)
		consumer := NewShipmentKafkaConsumer(nil, shipmentService)

		if err := consumer.handleMessage(context.Background(), commonkafka.CancelShipmentCommand, uuid.New(), "corr-12", []byte("not json")); !errors.Is(err, ErrMalformedMessage) {
			t.Errorf("err = %v, want %v", err, ErrMalformedMessage)
		}
	})

	t.Run("ignores unrecognized message types without calling the service", func(t *testing.T) {

		shipmentService := servicemocks.NewMockShipmentService(gomock.NewController(t))
		shipmentService.EXPECT().CreateShipment(gomock.Any(), gomock.Any(), gomock.Any(), gomock.Any()).Times(0)
		shipmentService.EXPECT().CancelByOrder(gomock.Any(), gomock.Any(), gomock.Any()).Times(0)
		consumer := NewShipmentKafkaConsumer(nil, shipmentService)

		if err := consumer.handleMessage(context.Background(), "SomeUnrelatedEvent", uuid.New(), "corr-5", []byte(`{}`)); err != nil {
			t.Errorf("err = %v, want nil", err)
		}
	})
}
