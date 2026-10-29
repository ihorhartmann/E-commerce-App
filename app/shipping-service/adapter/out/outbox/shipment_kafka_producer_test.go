package outbox

import (
	"context"
	"errors"
	kafkadto "shippingservice/adapter/out/outbox/dto"
	"testing"
	"time"

	kafkaconst "shippingservice/adapter/common/kafka"
	kafkautil "shippingservice/application/port/common/kafka"
	"shippingservice/application/port/out/outbox/dto"
	outboxmocks "shippingservice/application/port/out/outbox/mocks"
	"shippingservice/domain/model"
	"shippingservice/domain/value"

	"github.com/google/uuid"
	"go.opentelemetry.io/otel"
	"go.opentelemetry.io/otel/propagation"
	"go.opentelemetry.io/otel/trace"
	"go.uber.org/mock/gomock"
)

// testShipment builds a shipment a courier has; a cancelled one was refused by the recipient.
func testShipment(t *testing.T, orderID uint64, status model.ShipmentStatus) *model.Shipment {
	t.Helper()
	address, err := value.NewAddress("Kyiv", "01001", "Khreshchatyk 1", "")
	if err != nil {
		t.Fatalf("failed to build test address: %v", err)
	}
	now := time.Now()
	cancellationReason := model.NoCancellationReason
	if status == model.Cancelled {
		cancellationReason = model.RecipientRefused
	}
	shipment, err := model.NewExistingShipment(1, orderID, uuid.New(), address, status, model.HolderCourier, cancellationReason, now, now, 0)
	if err != nil {
		t.Fatalf("failed to build test shipment: %v", err)
	}
	return shipment
}

// wantOutboxMessage builds the expected outbox message via dto.NewOutboxMessage itself, the same
// constructor the producer uses - MessageID stays empty on both sides since the database, not this
// code, assigns it on insert.
func wantOutboxMessage(t *testing.T, messageType kafkaconst.MessageType, correlationID string, shipment *model.Shipment, payload any) dto.OutboxMessage {
	t.Helper()
	want, err := dto.NewOutboxMessage(correlationID, messageType.String(), kafkautil.FormatKafkaId(shipment.OrderId()), payload, "")
	if err != nil {
		t.Fatalf("failed to build expected outbox message: %v", err)
	}
	return want
}

func TestOutboxShipmentKafkaProducer_PublishShipmentStatusChanged(t *testing.T) {
	t.Run("inserts the mapped event for an InDelivery shipment", func(t *testing.T) {
		correlationID := "corr-1"
		orderID := uint64(42)
		shipment := testShipment(t, orderID, model.InDelivery)
		want := wantOutboxMessage(t, kafkaconst.ShipmentInDeliveryEvent, correlationID, shipment, kafkadto.ToShipmentStatusChangedEvent(shipment))

		repository := outboxmocks.NewMockMessageRepository(gomock.NewController(t))
		repository.EXPECT().Insert(gomock.Any(), want).Return(nil).Times(1)
		producer := NewOutboxShipmentKafkaProducer(repository)

		if err := producer.PublishShipmentStatusChanged(context.Background(), correlationID, shipment); err != nil {
			t.Errorf("err = %v, want nil", err)
		}
	})

	t.Run("inserts the event with the cancellation reason for a Cancelled shipment", func(t *testing.T) {
		correlationID := "corr-2"
		orderID := uint64(7)
		shipment := testShipment(t, orderID, model.Cancelled)
		want := wantOutboxMessage(t, kafkaconst.ShipmentCancelledEvent, correlationID, shipment,
			kafkadto.ShipmentCancelledEvent{OrderID: orderID, ShipmentID: shipment.Id(), Reason: "RecipientRefused"})

		repository := outboxmocks.NewMockMessageRepository(gomock.NewController(t))
		repository.EXPECT().Insert(gomock.Any(), want).Return(nil).Times(1)
		producer := NewOutboxShipmentKafkaProducer(repository)

		if err := producer.PublishShipmentStatusChanged(context.Background(), correlationID, shipment); err != nil {
			t.Errorf("err = %v, want nil", err)
		}
	})

	// There's no test for the "status has no mapped message type" error branch: model.NewShipment/
	// NewExistingShipment reject any status that isn't one of the known constants, so a
	// *model.Shipment reaching this method can never carry an unmapped status.

	t.Run("propagates the repository's error without swallowing it", func(t *testing.T) {
		wantErr := errors.New("db down")
		shipment := testShipment(t, 42, model.InDelivery)
		repository := outboxmocks.NewMockMessageRepository(gomock.NewController(t))
		repository.EXPECT().Insert(gomock.Any(), gomock.Any()).Return(wantErr).Times(1)
		producer := NewOutboxShipmentKafkaProducer(repository)

		if err := producer.PublishShipmentStatusChanged(context.Background(), "corr-1", shipment); !errors.Is(err, wantErr) {
			t.Errorf("err = %v, want %v", err, wantErr)
		}
	})
}

func TestOutboxShipmentKafkaProducer_PublishShipmentReturnedToInventory(t *testing.T) {
	t.Run("inserts a ShipmentReturnedToInventoryEvent with the status payload", func(t *testing.T) {
		correlationID := "corr-3"
		orderID := uint64(9)
		shipment := testShipment(t, orderID, model.Cancelled)
		want := wantOutboxMessage(t, kafkaconst.ShipmentReturnedToInventoryEvent, correlationID, shipment, kafkadto.ToShipmentStatusChangedEvent(shipment))

		repository := outboxmocks.NewMockMessageRepository(gomock.NewController(t))
		repository.EXPECT().Insert(gomock.Any(), want).Return(nil).Times(1)
		producer := NewOutboxShipmentKafkaProducer(repository)

		if err := producer.PublishShipmentReturnedToInventory(context.Background(), correlationID, shipment); err != nil {
			t.Errorf("err = %v, want nil", err)
		}
	})

	t.Run("keeps the traceparent of the span the message is written in", func(t *testing.T) {
		otel.SetTextMapPropagator(propagation.TraceContext{})
		traceID := "4bf92f3577b34da6a3ce929d0e0e4736"
		spanID := "00f067aa0ba902b7"
		ctx := trace.ContextWithSpanContext(context.Background(), trace.NewSpanContext(trace.SpanContextConfig{
			TraceID:    mustTraceID(t, traceID),
			SpanID:     mustSpanID(t, spanID),
			TraceFlags: trace.FlagsSampled,
		}))
		correlationID := "corr-4"
		shipment := testShipment(t, uint64(10), model.Cancelled)
		var gotMessage dto.OutboxMessage

		repository := outboxmocks.NewMockMessageRepository(gomock.NewController(t))
		repository.EXPECT().Insert(gomock.Any(), gomock.Any()).DoAndReturn(func(_ context.Context, message dto.OutboxMessage) error {
			gotMessage = message
			return nil
		}).Times(1)
		producer := NewOutboxShipmentKafkaProducer(repository)

		if err := producer.PublishShipmentReturnedToInventory(ctx, correlationID, shipment); err != nil {
			t.Errorf("err = %v, want nil", err)
		}
		if want := "00-" + traceID + "-" + spanID + "-01"; gotMessage.Traceparent != want {
			t.Errorf("traceparent = %q, want %q", gotMessage.Traceparent, want)
		}
	})
}

func mustTraceID(t *testing.T, hex string) trace.TraceID {
	t.Helper()
	traceID, err := trace.TraceIDFromHex(hex)
	if err != nil {
		t.Fatalf("invalid trace id %q: %v", hex, err)
	}
	return traceID
}

func mustSpanID(t *testing.T, hex string) trace.SpanID {
	t.Helper()
	spanID, err := trace.SpanIDFromHex(hex)
	if err != nil {
		t.Fatalf("invalid span id %q: %v", hex, err)
	}
	return spanID
}
