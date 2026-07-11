package kafka

import (
	"context"
	commonkafka "shippingservice/adapter/common/kafka"
	"shippingservice/adapter/out/kafka/common"
	portout "shippingservice/application/port/out/kafka"
	"shippingservice/domain/model"

	"github.com/google/uuid"
)

const shipmentStatusChangedEventType commonkafka.EventType = "shipment.status.changed"

type KafkaGoShipmentProducer struct {
	portout.ShipmentProducer
	producer common.KafkaMessageProducer
}

func NewKafkaGoShipmentProducer(producer common.KafkaMessageProducer) *KafkaGoShipmentProducer {
	return &KafkaGoShipmentProducer{producer: producer}
}

func (p *KafkaGoShipmentProducer) PublishShipmentStatusChanged(_ context.Context, shipment *model.Shipment) error {
	return p.producer.Send(shipmentStatusChangedEventType, uuid.Nil, shipment)
}

func (p *KafkaGoShipmentProducer) Close() error {
	return p.producer.Close()
}
