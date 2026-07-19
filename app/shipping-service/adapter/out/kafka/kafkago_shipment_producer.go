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
	eventType, err := shipmentStatusEventType(shipment.Status())
	if err != nil {
		return err
	}

	payload := dto.ToShipmentStatusChangedDto(shipment)
	key := strconv.FormatUint(shipment.OrderId(), 10)
	return p.producer.Send(eventType, correlationID, key, payload)
}

func shipmentStatusEventType(status model.ShipmentStatus) (commonkafka.EventType, error) {
	switch status {
	case model.Created:
		return commonkafka.ShipmentCreatedEventType, nil
	case model.InDelivery:
		return commonkafka.ShipmentInDeliveryEventType, nil
	case model.Delivered:
		return commonkafka.ShipmentDeliveredEventType, nil
	case model.Cancelled:
		return commonkafka.ShipmentCancelledEventType, nil
	case model.Lost:
		return commonkafka.ShipmentLostEventType, nil
	case model.ReturnedToInventory:
		return commonkafka.ShipmentReturnedToInventoryEventType, nil
	default:
		return "", fmt.Errorf("no kafka event type mapped for shipment status %q", status)
	}
}

func (p *KafkaGoShipmentProducer) PublishShipmentCreationFailed(_ context.Context, correlationID string, orderID uint64) error {
	key := strconv.FormatUint(orderID, 10)
	return p.producer.Send(commonkafka.ShipmentCreationFailedEventType, correlationID, key, dto.ToShipmentCreationFailedDto(orderID))
}

func (p *KafkaGoShipmentProducer) Close() error {
	return p.producer.Close()
}
