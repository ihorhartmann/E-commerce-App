package kafka

import (
	"context"
	"encoding/json"
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
	default:
		return nil
	}
}

func (c *ShipmentKafkaConsumer) handleCreateShipment(ctx context.Context, msg idempotencydto.KafkaMessageMetadata, payload []byte) error {
	var command dto.CreateShipmentCommand
	if err := json.Unmarshal(payload, &command); err != nil {
		return NonRetryable(err)
	}

	address, err := dto.FromAddressDTO(command.Address)
	if err != nil {
		return NonRetryable(err)
	}

	_, err = c.shipmentService.CreateShipment(ctx, msg, command.OrderID, command.CustomerID, address)
	return classify(err)
}
