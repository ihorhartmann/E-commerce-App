package kafka

import (
	"context"
	"encoding/json"
	commonkafka "shippingservice/application/port/common/kafka"

	"shippingservice/adapter/in/kafka/common"
	"shippingservice/adapter/in/kafka/dto"
	portin "shippingservice/application/port/in"
)

type ShipmentConsumer struct {
	consumer        common.KafkaMessageConsumer
	shipmentService portin.ShipmentService
}

func NewShipmentConsumer(
	consumer common.KafkaMessageConsumer,
	shipmentService portin.ShipmentService,
) *ShipmentConsumer {
	return &ShipmentConsumer{
		consumer:        consumer,
		shipmentService: shipmentService,
	}
}

func (c *ShipmentConsumer) Start() {
	c.consumer.ConsumeAsync(c.handleMessage)
}

func (c *ShipmentConsumer) Close() error {
	return c.consumer.Close()
}

func (c *ShipmentConsumer) handleMessage(messageType commonkafka.MessageType, _ string, correlationID string, payload []byte) error {
	switch messageType {
	case commonkafka.CreateShipmentCommand:
		return c.handleCreateShipment(correlationID, payload)
	default:
		return nil
	}
}

func (c *ShipmentConsumer) handleCreateShipment(correlationID string, payload []byte) error {
	var command dto.CreateShipmentCommand
	if err := json.Unmarshal(payload, &command); err != nil {
		return err
	}

	address := dto.FromAddressMessage(command.Address)

	_, err := c.shipmentService.CreateShipment(context.Background(), correlationID, command.OrderID, address)
	return err
}
