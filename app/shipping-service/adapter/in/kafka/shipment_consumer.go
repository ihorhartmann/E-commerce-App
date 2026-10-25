package kafka

import (
	"context"
	"encoding/json"
	"log/slog"
	commonkafka "shippingservice/adapter/common/kafka"
	"shippingservice/adapter/in/kafka/common"
	"shippingservice/adapter/in/kafka/dto"
	portin "shippingservice/application/port/in"
	idempotencydto "shippingservice/application/port/out/idempotency/dto"

	"github.com/google/uuid"
)

type ShipmentKafkaConsumer struct {
	consumer        common.KafkaMessageConsumer
	shipmentService portin.ShipmentService
}

func NewShipmentKafkaConsumer(
	consumer common.KafkaMessageConsumer,
	shipmentService portin.ShipmentService,
) *ShipmentKafkaConsumer {
	return &ShipmentKafkaConsumer{
		consumer:        consumer,
		shipmentService: shipmentService,
	}
}

func (c *ShipmentKafkaConsumer) Start() {
	c.consumer.ConsumeAsync(c.handleMessage)
}

func (c *ShipmentKafkaConsumer) Close() error {
	return c.consumer.Close()
}

func (c *ShipmentKafkaConsumer) handleMessage(ctx context.Context, messageType commonkafka.MessageType, messageID uuid.UUID, correlationID string, payload []byte) error {
	msg := idempotencydto.KafkaMessageMetadata{MessageID: messageID, CorrelationID: correlationID, MessageType: string(messageType)}

	switch messageType {
	case commonkafka.CreateShipmentCommand:
		return c.handleCreateShipment(ctx, msg, payload)
	case commonkafka.CancelShipmentCommand:
		return c.handleCancelShipment(ctx, msg, payload)
	default:
		return nil
	}
}

func (c *ShipmentKafkaConsumer) handleCreateShipment(ctx context.Context, msg idempotencydto.KafkaMessageMetadata, payload []byte) error {
	var command dto.CreateShipmentCommand
	if err := json.Unmarshal(payload, &command); err != nil {
		return malformedPayloadErr(msg, err)
	}

	address, err := dto.FromAddressDTO(command.Address)
	if err != nil {
		return err // already ours: ErrInvalidAddress, permanent
	}

	_, err = c.shipmentService.CreateShipment(ctx, msg, command.OrderID, address)
	return err
}

func (c *ShipmentKafkaConsumer) handleCancelShipment(ctx context.Context, msg idempotencydto.KafkaMessageMetadata, payload []byte) error {
	var command dto.CancelShipmentCommand
	if err := json.Unmarshal(payload, &command); err != nil {
		return malformedPayloadErr(msg, err)
	}

	cancelled, err := c.shipmentService.CancelByOrder(ctx, msg, command.OrderID)
	if err == nil && !cancelled {
		slog.Info("nothing to cancel: the shipment has already ended, or the command was already handled", "orderId", command.OrderID, "correlationId", msg.CorrelationID)
	}

	return err
}

// malformedPayloadErr reports a payload that doesn't decode - permanent, so the message is dead-lettered at once.
func malformedPayloadErr(msg idempotencydto.KafkaMessageMetadata, err error) error {
	return ErrMalformedMessage.
		WithAttrs(slog.String("messageType", msg.MessageType), slog.String("messageID", msg.MessageID.String())).
		WithCause(err)
}
