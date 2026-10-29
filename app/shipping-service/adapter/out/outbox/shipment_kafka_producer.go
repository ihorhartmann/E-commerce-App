package outbox

import (
	"context"
	"log/slog"
	kafkadto "shippingservice/adapter/out/outbox/dto"

	kafkaconst "shippingservice/adapter/common/kafka"
	kafkautil "shippingservice/application/port/common/kafka"
	portout "shippingservice/application/port/out/kafka"
	"shippingservice/application/port/out/outbox"
	"shippingservice/application/port/out/outbox/dto"
	"shippingservice/domain/model"

	"go.opentelemetry.io/otel"
	"go.opentelemetry.io/otel/propagation"
)

// traceparentKey is the W3C trace context header the propagator writes the current span into.
const traceparentKey = "traceparent"

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
		currentTraceparent(ctx),
	)

	if err != nil {
		return ErrOutboxFailure.
			WithInternalMessage("build outbox message").
			WithAttrs(slog.String("messageType", messageType.String()), slog.Uint64("orderID", orderID)).
			WithCause(err)
	}

	return p.repository.Insert(ctx, message)
}

// currentTraceparent is the W3C traceparent of the span ctx carries, kept with the message so the relay publishes it in
// the same trace; "" outside a trace.
func currentTraceparent(ctx context.Context) string {
	carrier := propagation.MapCarrier{}
	otel.GetTextMapPropagator().Inject(ctx, carrier)
	return carrier.Get(traceparentKey)
}
