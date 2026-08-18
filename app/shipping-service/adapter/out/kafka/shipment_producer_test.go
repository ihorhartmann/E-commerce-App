package kafka

import (
	"context"
	"testing"
	"time"

	kafkaconst "shippingservice/adapter/common/kafka"
	kafkamocks "shippingservice/adapter/out/kafka/common/mocks"
	"shippingservice/adapter/out/kafka/dto"
	kafkautil "shippingservice/application/port/common/kafka"
	"shippingservice/domain/model"
	"shippingservice/domain/value"

	"go.uber.org/mock/gomock"
)

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

func TestShipmentKafkaProducer_PublishShipmentStatusChanged(t *testing.T) {
	t.Run("publishes the mapped event for an InDelivery shipment", func(t *testing.T) {
		correlationID := "corr-1"
		orderID := uint64(42)
		shipment := testShipment(t, orderID, "TRACK-1", model.InDelivery)
		wantKey := kafkautil.FormatKafkaId(orderID)
		wantPayload := dto.ToShipmentStatusChangedEvent(shipment)

		messageProducer := kafkamocks.NewMockKafkaMessageProducer(gomock.NewController(t))
		messageProducer.EXPECT().
			Send(kafkaconst.ShipmentInDeliveryEvent, correlationID, wantKey, wantPayload).
			Return(nil).
			Times(1)
		producer := NewShipmentKafkaProducer(messageProducer)

		if err := producer.PublishShipmentStatusChanged(context.Background(), correlationID, shipment); err != nil {
			t.Errorf("err = %v, want nil", err)
		}
	})

	t.Run("publishes the mapped event for a Cancelled shipment", func(t *testing.T) {
		correlationID := "corr-2"
		orderID := uint64(7)
		shipment := testShipment(t, orderID, "TRACK-2", model.Cancelled)
		wantKey := kafkautil.FormatKafkaId(orderID)
		wantPayload := dto.ToShipmentStatusChangedEvent(shipment)

		messageProducer := kafkamocks.NewMockKafkaMessageProducer(gomock.NewController(t))
		messageProducer.EXPECT().
			Send(kafkaconst.ShipmentCancelledEvent, correlationID, wantKey, wantPayload).
			Return(nil).
			Times(1)
		producer := NewShipmentKafkaProducer(messageProducer)

		if err := producer.PublishShipmentStatusChanged(context.Background(), correlationID, shipment); err != nil {
			t.Errorf("err = %v, want nil", err)
		}
	})

	// There's no test for the "status has no mapped message type" error
	// branch: model.NewExistingShipment/NewShipment reject any status that
	// isn't one of the known constants, so a *model.Shipment reaching this
	// method can never carry an unmapped status.
}

func TestShipmentKafkaProducer_PublishShipmentReturnedToInventory(t *testing.T) {
	t.Run("publishes a ShipmentReturnedToInventoryEvent with the status payload", func(t *testing.T) {
		correlationID := "corr-3"
		orderID := uint64(9)
		shipment := testShipment(t, orderID, "TRACK-3", model.Cancelled)
		wantKey := kafkautil.FormatKafkaId(orderID)
		wantPayload := dto.ToShipmentStatusChangedEvent(shipment)

		messageProducer := kafkamocks.NewMockKafkaMessageProducer(gomock.NewController(t))
		messageProducer.EXPECT().
			Send(kafkaconst.ShipmentReturnedToInventoryEvent, correlationID, wantKey, wantPayload).
			Return(nil).
			Times(1)
		producer := NewShipmentKafkaProducer(messageProducer)

		if err := producer.PublishShipmentReturnedToInventory(context.Background(), correlationID, shipment); err != nil {
			t.Errorf("err = %v, want nil", err)
		}
	})
}

func TestShipmentKafkaProducer_PublishShipmentCreationFailed(t *testing.T) {
	t.Run("publishes a ShipmentCreationFailedEvent for the order", func(t *testing.T) {
		correlationID := "corr-4"
		orderID := uint64(13)
		wantKey := kafkautil.FormatKafkaId(orderID)
		wantPayload := dto.ShipmentCreationFailedEvent{OrderID: orderID}

		messageProducer := kafkamocks.NewMockKafkaMessageProducer(gomock.NewController(t))
		messageProducer.EXPECT().
			Send(kafkaconst.ShipmentCreationFailedEvent, correlationID, wantKey, wantPayload).
			Return(nil).
			Times(1)
		producer := NewShipmentKafkaProducer(messageProducer)

		if err := producer.PublishShipmentCreationFailed(context.Background(), correlationID, orderID); err != nil {
			t.Errorf("err = %v, want nil", err)
		}
	})
}
