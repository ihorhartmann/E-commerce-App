package kafka

import (
	"context"
	kafkaconst "shippingservice/adapter/common/kafka"
	kafkautil "shippingservice/application/port/common/kafka"

	"shippingservice/adapter/out/kafka/common"
	"shippingservice/adapter/out/kafka/dto"
	portout "shippingservice/application/port/out/kafka"
	"shippingservice/domain/model"
)

type ShipmentKafkaProducer struct {
	portout.ShipmentKafkaProducer
	producer common.KafkaMessageProducer
}

func NewShipmentKafkaProducer(producer common.KafkaMessageProducer) portout.ShipmentKafkaProducer {
	return &ShipmentKafkaProducer{producer: producer}
}

func (p *ShipmentKafkaProducer) PublishShipmentStatusChanged(_ context.Context, correlationID string, shipment *model.Shipment) error {
	messageType, err := kafkaconst.ConvertShipmentStatusMessageType(shipment.Status())
	if err != nil {
		return err
	}

	payload := dto.ToShipmentStatusChangedEvent(shipment)
	key := kafkautil.FormatKafkaId(shipment.OrderId())
	return p.producer.Send(messageType, correlationID, key, payload)
}

// PublishShipmentReturnedToInventory is published outside of PublishShipmentStatusChanged
// because it reflects a holder change, not a status change - the shipment's status stays
// Cancelled throughout.
func (p *ShipmentKafkaProducer) PublishShipmentReturnedToInventory(_ context.Context, correlationID string, shipment *model.Shipment) error {
	payload := dto.ToShipmentStatusChangedEvent(shipment)
	key := kafkautil.FormatKafkaId(shipment.OrderId())
	return p.producer.Send(kafkaconst.ShipmentReturnedToInventoryEvent, correlationID, key, payload)
}

func (p *ShipmentKafkaProducer) PublishShipmentCreationFailed(_ context.Context, correlationID string, orderID uint64) error {
	key := kafkautil.FormatKafkaId(orderID)
	return p.producer.Send(kafkaconst.ShipmentCreationFailedEvent, correlationID, key, dto.ShipmentCreationFailedEvent{OrderID: orderID})
}

func (p *ShipmentKafkaProducer) Close() error {
	return p.producer.Close()
}
