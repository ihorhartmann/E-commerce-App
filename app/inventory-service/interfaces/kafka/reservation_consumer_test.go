package kafka

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"testing"

	"inventoryservice/application/dto"
	"inventoryservice/application/port/common/kafka"
	"inventoryservice/application/port/in/mocks"
	idempotencydto "inventoryservice/application/port/out/idempotency/dto"
	kafkadto "inventoryservice/interfaces/kafka/dto"

	"inventoryservice/domain"
	"inventoryservice/domain/model"

	"go.uber.org/mock/gomock"

	"github.com/google/uuid"
)

func TestReservationKafkaConsumer_handleMessage(t *testing.T) {
	t.Run("dispatches CancelReservationCommand to the reservation service", func(t *testing.T) {
		orderID := uint64(42)
		payload, err := json.Marshal(kafkadto.CancelReservationCommand{OrderID: orderID})
		if err != nil {
			t.Fatalf("failed to marshal command: %v", err)
		}
		msg := idempotencydto.KafkaMessageMetadata{MessageID: uuid.New(), CorrelationID: "corr-1", MessageType: string(kafka.CancelReservationCommand)}

		reservationService := mocks.NewMockReservationService(gomock.NewController(t))
		reservationService.EXPECT().Cancel(gomock.Any(), msg, orderID).Return(nil).Times(1)
		productService := mocks.NewMockProductService(gomock.NewController(t))
		consumer := NewReservationKafkaConsumer(nil, reservationService, productService)

		if err = consumer.handleMessage(context.Background(), kafka.CancelReservationCommand, msg.MessageID, msg.CorrelationID, payload); err != nil {
			t.Errorf("err = %v, want nil", err)
		}
	})

	t.Run("returns a transient service error unmarked, so it gets retried", func(t *testing.T) {
		orderID := uint64(43)
		payload, err := json.Marshal(kafkadto.CancelReservationCommand{OrderID: orderID})
		if err != nil {
			t.Fatalf("failed to marshal command: %v", err)
		}
		msg := idempotencydto.KafkaMessageMetadata{MessageID: uuid.New(), CorrelationID: "corr-8", MessageType: string(kafka.CancelReservationCommand)}
		serviceErr := errors.New("cancel reservation failed")

		reservationService := mocks.NewMockReservationService(gomock.NewController(t))
		reservationService.EXPECT().Cancel(gomock.Any(), msg, orderID).Return(serviceErr).Times(1)
		productService := mocks.NewMockProductService(gomock.NewController(t))
		consumer := NewReservationKafkaConsumer(nil, reservationService, productService)

		err = consumer.handleMessage(context.Background(), kafka.CancelReservationCommand, msg.MessageID, msg.CorrelationID, payload)

		if !errors.Is(err, serviceErr) {
			t.Errorf("err = %v, want %v", err, serviceErr)
		}
		if errors.Is(err, ErrNonRetryable) {
			t.Errorf("err = %v, want a retryable error", err)
		}
	})

	t.Run("marks a service error caused by invalid input as non-retryable", func(t *testing.T) {
		orderID := uint64(44)
		payload, err := json.Marshal(kafkadto.CancelReservationCommand{OrderID: orderID})
		if err != nil {
			t.Fatalf("failed to marshal command: %v", err)
		}
		msg := idempotencydto.KafkaMessageMetadata{MessageID: uuid.New(), CorrelationID: "corr-9", MessageType: string(kafka.CancelReservationCommand)}
		serviceErr := fmt.Errorf("cancel reservation: %w", domain.ErrInvalidInput)

		reservationService := mocks.NewMockReservationService(gomock.NewController(t))
		reservationService.EXPECT().Cancel(gomock.Any(), msg, orderID).Return(serviceErr).Times(1)
		productService := mocks.NewMockProductService(gomock.NewController(t))
		consumer := NewReservationKafkaConsumer(nil, reservationService, productService)

		err = consumer.handleMessage(context.Background(), kafka.CancelReservationCommand, msg.MessageID, msg.CorrelationID, payload)

		if !errors.Is(err, ErrNonRetryable) {
			t.Errorf("err = %v, want a non-retryable error", err)
		}
		if !errors.Is(err, domain.ErrInvalidInput) {
			t.Errorf("err = %v, want it to wrap %v", err, domain.ErrInvalidInput)
		}
	})

	t.Run("marks a service error caused by a domain rule rejecting the command as non-retryable", func(t *testing.T) {
		domainErrs := []error{
			model.ErrInsufficientStock,
			model.ErrForeignReservation,
			model.ErrInvalidHolderTransition,
			model.ErrReservationNotHeldByCourier,
		}
		for _, domainErr := range domainErrs {
			orderID := uint64(45)
			payload, err := json.Marshal(kafkadto.FulfillReservationCommand{OrderID: orderID})
			if err != nil {
				t.Fatalf("failed to marshal command: %v", err)
			}
			msg := idempotencydto.KafkaMessageMetadata{MessageID: uuid.New(), CorrelationID: "corr-10", MessageType: string(kafka.FulfillReservationCommand)}
			serviceErr := fmt.Errorf("fulfill reservation: %w", domainErr)

			reservationService := mocks.NewMockReservationService(gomock.NewController(t))
			reservationService.EXPECT().Fulfill(gomock.Any(), msg, orderID).Return(serviceErr).Times(1)
			productService := mocks.NewMockProductService(gomock.NewController(t))
			consumer := NewReservationKafkaConsumer(nil, reservationService, productService)

			err = consumer.handleMessage(context.Background(), kafka.FulfillReservationCommand, msg.MessageID, msg.CorrelationID, payload)

			if !errors.Is(err, ErrNonRetryable) {
				t.Errorf("%v: err = %v, want a non-retryable error", domainErr, err)
			}
			if !errors.Is(err, domainErr) {
				t.Errorf("err = %v, want it to wrap %v", err, domainErr)
			}
		}
	})

	t.Run("dispatches FulfillReservationCommand to the reservation service", func(t *testing.T) {
		orderID := uint64(7)
		payload, err := json.Marshal(kafkadto.FulfillReservationCommand{OrderID: orderID})
		if err != nil {
			t.Fatalf("failed to marshal command: %v", err)
		}
		msg := idempotencydto.KafkaMessageMetadata{MessageID: uuid.New(), CorrelationID: "corr-2", MessageType: string(kafka.FulfillReservationCommand)}

		reservationService := mocks.NewMockReservationService(gomock.NewController(t))
		reservationService.EXPECT().Fulfill(gomock.Any(), msg, orderID).Return(nil).Times(1)
		productService := mocks.NewMockProductService(gomock.NewController(t))
		consumer := NewReservationKafkaConsumer(nil, reservationService, productService)

		if err = consumer.handleMessage(context.Background(), kafka.FulfillReservationCommand, msg.MessageID, msg.CorrelationID, payload); err != nil {
			t.Errorf("err = %v, want nil", err)
		}
	})

	t.Run("dispatches HoldReservationCommand to the reservation service", func(t *testing.T) {
		orderID := uint64(13)
		payload, err := json.Marshal(kafkadto.HoldReservationCommand{OrderID: orderID})
		if err != nil {
			t.Fatalf("failed to marshal command: %v", err)
		}
		msg := idempotencydto.KafkaMessageMetadata{MessageID: uuid.New(), CorrelationID: "corr-3", MessageType: string(kafka.HoldReservationCommand)}

		reservationService := mocks.NewMockReservationService(gomock.NewController(t))
		reservationService.EXPECT().HoldByCourier(gomock.Any(), msg, orderID).Return(nil).Times(1)
		productService := mocks.NewMockProductService(gomock.NewController(t))
		consumer := NewReservationKafkaConsumer(nil, reservationService, productService)

		if err = consumer.handleMessage(context.Background(), kafka.HoldReservationCommand, msg.MessageID, msg.CorrelationID, payload); err != nil {
			t.Errorf("err = %v, want nil", err)
		}
	})

	t.Run("dispatches RestockCommand to the product service with the mapped items", func(t *testing.T) {
		orderID := uint64(99)
		items := []kafkadto.RestockItem{{ProductID: 1, Quantity: 5}, {ProductID: 2, Quantity: 3}}
		payload, err := json.Marshal(kafkadto.RestockCommand{OrderID: orderID, Items: items})
		if err != nil {
			t.Fatalf("failed to marshal command: %v", err)
		}
		wantReservationDTOs := []dto.ReservationDTO{{ProductID: 1, Quantity: 5}, {ProductID: 2, Quantity: 3}}
		msg := idempotencydto.KafkaMessageMetadata{MessageID: uuid.New(), CorrelationID: "corr-4", MessageType: string(kafka.RestockCommand)}

		reservationService := mocks.NewMockReservationService(gomock.NewController(t))
		reservationService.EXPECT().Cancel(gomock.Any(), gomock.Any(), gomock.Any()).Times(0)
		reservationService.EXPECT().Fulfill(gomock.Any(), gomock.Any(), gomock.Any()).Times(0)
		productService := mocks.NewMockProductService(gomock.NewController(t))
		productService.EXPECT().Restock(gomock.Any(), msg, wantReservationDTOs).Return(nil).Times(1)
		consumer := NewReservationKafkaConsumer(nil, reservationService, productService)

		if err = consumer.handleMessage(context.Background(), kafka.RestockCommand, msg.MessageID, msg.CorrelationID, payload); err != nil {
			t.Errorf("err = %v, want nil", err)
		}
	})

	t.Run("returns a non-retryable error without calling the reservation service when the payload is malformed JSON", func(t *testing.T) {
		reservationService := mocks.NewMockReservationService(gomock.NewController(t))
		reservationService.EXPECT().Cancel(gomock.Any(), gomock.Any(), gomock.Any()).Times(0)
		productService := mocks.NewMockProductService(gomock.NewController(t))
		productService.EXPECT().Restock(gomock.Any(), gomock.Any(), gomock.Any()).Times(0)
		consumer := NewReservationKafkaConsumer(nil, reservationService, productService)

		if err := consumer.handleMessage(context.Background(), kafka.CancelReservationCommand, uuid.New(), "corr-5", []byte("not json")); !errors.Is(err, ErrNonRetryable) {
			t.Errorf("err = %v, want a non-retryable unmarshal error", err)
		}
	})

	t.Run("returns a non-retryable error without calling the product service when the RestockCommand payload is malformed JSON", func(t *testing.T) {
		reservationService := mocks.NewMockReservationService(gomock.NewController(t))
		reservationService.EXPECT().Cancel(gomock.Any(), gomock.Any(), gomock.Any()).Times(0)
		productService := mocks.NewMockProductService(gomock.NewController(t))
		productService.EXPECT().Restock(gomock.Any(), gomock.Any(), gomock.Any()).Times(0)
		consumer := NewReservationKafkaConsumer(nil, reservationService, productService)

		if err := consumer.handleMessage(context.Background(), kafka.RestockCommand, uuid.New(), "corr-6", []byte("not json")); !errors.Is(err, ErrNonRetryable) {
			t.Errorf("err = %v, want a non-retryable unmarshal error", err)
		}
	})

	t.Run("ignores unrecognized message types without calling either service", func(t *testing.T) {
		reservationService := mocks.NewMockReservationService(gomock.NewController(t))
		reservationService.EXPECT().Fulfill(gomock.Any(), gomock.Any(), gomock.Any()).Times(0)
		productService := mocks.NewMockProductService(gomock.NewController(t))
		consumer := NewReservationKafkaConsumer(nil, reservationService, productService)

		if err := consumer.handleMessage(context.Background(), "SomeUnrelatedEvent", uuid.New(), "corr-7", []byte(`{}`)); err != nil {
			t.Errorf("err = %v, want nil", err)
		}
	})
}
