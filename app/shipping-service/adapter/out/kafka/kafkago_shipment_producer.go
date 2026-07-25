package kafka

import (
	"context"
	commonkafka "shippingservice/application/port/common/kafka"

	"shippingservice/adapter/out/kafka/common"
	"shippingservice/adapter/out/kafka/dto"
	portout "shippingservice/application/port/out/kafka"
	"shippingservice/domain/model"
)

type KafkaGoShipmentProducer struct {
	portout.ShipmentProducer
	producer common.KafkaMessageProducer
}

func NewKafkaGoShipmentProducer(producer common.KafkaMessageProducer) *KafkaGoShipmentProducer {
	return &KafkaGoShipmentProducer{producer: producer}
}

func (p *KafkaGoShipmentProducer) PublishShipmentStatusChanged(_ context.Context, correlationID string, shipment *model.Shipment) error {
	messageType, err := commonkafka.ConvertShipmentStatusMessageType(shipment.Status())
	if err != nil {
		return err
	}

	payload := dto.ToShipmentStatusChangedEvent(shipment)
	key := commonkafka.FormatKafkaId(shipment.OrderId())
	return p.producer.Send(messageType, correlationID, key, payload)
}

// PublishShipmentReturnedToInventory is published outside of PublishShipmentStatusChanged
// because it reflects a holder change, not a status change - the shipment's status stays
// Cancelled throughout.
func (p *KafkaGoShipmentProducer) PublishShipmentReturnedToInventory(_ context.Context, correlationID string, shipment *model.Shipment) error {
	payload := dto.ToShipmentStatusChangedEvent(shipment)
	key := commonkafka.FormatKafkaId(shipment.OrderId())
	return p.producer.Send(commonkafka.ShipmentReturnedToInventoryEvent, correlationID, key, payload)
}

func (p *KafkaGoShipmentProducer) PublishShipmentCreationFailed(_ context.Context, correlationID string, orderID uint64) error {
	key := commonkafka.FormatKafkaId(orderID)
	return p.producer.Send(commonkafka.ShipmentCreationFailedEvent, correlationID, key, dto.ToShipmentCreationFailedEvent(orderID))
}

func (p *KafkaGoShipmentProducer) Close() error {
	return p.producer.Close()
}
