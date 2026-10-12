package outbox

import (
	"context"
	kafkadto "shippingservice/adapter/out/outbox/dto"

	kafkaconst "shippingservice/adapter/common/kafka"
	kafkautil "shippingservice/application/port/common/kafka"
	portout "shippingservice/application/port/out/kafka"
	"shippingservice/application/port/out/outbox"
	"shippingservice/application/port/out/outbox/dto"
	"shippingservice/domain/model"
)

// ShipmentKafkaProducer implements portout.ShipmentKafkaProducer by writing to the
// transactional outbox instead of publishing to Kafka directly - OutboxMessageRelay is what
// actually delivers the message afterwards, so the shipment's DB mutation and the Kafka publish
// never need to succeed atomically.
type ShipmentKafkaProducer struct {
	portout.ShipmentKafkaProducer
	repository outbox.MessageRepository
}

func NewOutboxShipmentKafkaProducer(repository outbox.MessageRepository) portout.ShipmentKafkaProducer {
	return &ShipmentKafkaProducer{repository: repository}
}

func (p *ShipmentKafkaProducer) PublishShipmentStatusChanged(ctx context.Context, correlationID string, shipment *model.Shipment) error {
	messageType, err := kafkaconst.ConvertShipmentStatusMessageType(shipment.Status())
	if err != nil {
		return err
	}

	// a cancellation is the only status change that carries more than which shipment it is about
	if messageType == kafkaconst.ShipmentCancelledEvent {
		return p.insert(ctx, messageType, correlationID, shipment.OrderId(), kafkadto.ToShipmentCancelledEvent(shipment))
	}

	return p.insert(ctx, messageType, correlationID, shipment.OrderId(), kafkadto.ToShipmentStatusChangedEvent(shipment))
}

func (p *ShipmentKafkaProducer) PublishShipmentReturnedToInventory(ctx context.Context, correlationID string, shipment *model.Shipment) error {
	return p.insert(ctx, kafkaconst.ShipmentReturnedToInventoryEvent, correlationID, shipment.OrderId(), kafkadto.ToShipmentStatusChangedEvent(shipment))
}

func (p *ShipmentKafkaProducer) insert(ctx context.Context, messageType kafkaconst.MessageType, correlationID string, orderID uint64, payload any) error {
	message, err := dto.NewOutboxMessage(
		correlationID,
		messageType.String(),
		kafkautil.FormatKafkaId(orderID),
		payload,
	)

	if err != nil {
		return err
	}

	return p.repository.Insert(ctx, message)
}
