package kafka

import (
	"context"
	"encoding/json"
	commonkafka "shippingservice/adapter/common/kafka"
	"shippingservice/adapter/in/kafka/common"
	"shippingservice/adapter/in/kafka/dto"
	portin "shippingservice/application/port/in"
	idempotencydto "shippingservice/application/port/out/idempotency/dto"
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

func (c *ShipmentKafkaConsumer) handleMessage(messageType commonkafka.MessageType, messageID string, correlationID string, payload []byte) error {
	msg := idempotencydto.KafkaMessageMetadata{MessageID: messageID, CorrelationID: correlationID, MessageType: string(messageType)}

	switch messageType {
	case commonkafka.CreateShipmentCommand:
		return c.handleCreateShipment(msg, payload)
	default:
		return nil
	}
}

func (c *ShipmentKafkaConsumer) handleCreateShipment(msg idempotencydto.KafkaMessageMetadata, payload []byte) error {
	var command dto.CreateShipmentCommand
	if err := json.Unmarshal(payload, &command); err != nil {
		return err
	}

	address, err := dto.FromAddressDTO(command.Address)
	if err != nil {
		return err
	}

	_, err = c.shipmentService.CreateShipment(context.Background(), msg, command.OrderID, address)
	return err
}
