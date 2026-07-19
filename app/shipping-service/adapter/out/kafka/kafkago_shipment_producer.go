package kafka

import (
	"context"
	"fmt"
	"strconv"

	commonkafka "shippingservice/adapter/common/kafka"
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
	eventType, err := convertShipmentStatusEventType(shipment.Status())
	if err != nil {
		return err
	}

	payload := dto.ToShipmentStatusChangedDto(shipment)
	key := strconv.FormatUint(shipment.OrderId(), 10)
	return p.producer.Send(eventType, correlationID, key, payload)
}

var shipmentStatusEventTypes = map[model.ShipmentStatus]commonkafka.EventType{
	model.Created:    commonkafka.ShipmentCreatedEventType,
	model.InDelivery: commonkafka.ShipmentInDeliveryEventType,
	model.Delivered:  commonkafka.ShipmentDeliveredEventType,
	model.Cancelled:  commonkafka.ShipmentCancelledEventType,
	model.Lost:       commonkafka.ShipmentLostEventType,
}

func convertShipmentStatusEventType(status model.ShipmentStatus) (commonkafka.EventType, error) {
	eventType, ok := shipmentStatusEventTypes[status]
	if !ok {
		return "", fmt.Errorf("no kafka event type mapped for shipment status %q", status)
	}

	return eventType, nil
}

// PublishShipmentReturnedToInventory is published outside of PublishShipmentStatusChanged
// because it reflects a holder change, not a status change - the shipment's status stays
// Cancelled throughout.
func (p *KafkaGoShipmentProducer) PublishShipmentReturnedToInventory(_ context.Context, correlationID string, shipment *model.Shipment) error {
	payload := dto.ToShipmentStatusChangedDto(shipment)
	key := strconv.FormatUint(shipment.OrderId(), 10)
	return p.producer.Send(commonkafka.ShipmentReturnedToInventoryEventType, correlationID, key, payload)
}

func (p *KafkaGoShipmentProducer) PublishShipmentCreationFailed(_ context.Context, correlationID string, orderID uint64) error {
	key := strconv.FormatUint(orderID, 10)
	return p.producer.Send(commonkafka.ShipmentCreationFailedEventType, correlationID, key, dto.ToShipmentCreationFailedDto(orderID))
}

func (p *KafkaGoShipmentProducer) Close() error {
	return p.producer.Close()
}
