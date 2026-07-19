package kafka

import (
	"context"
	"encoding/json"

	commonkafka "shippingservice/adapter/common/kafka"
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

func (c *ShipmentConsumer) handleMessage(eventType commonkafka.EventType, _ string, correlationID string, payload []byte) error {
	switch eventType {
	case commonkafka.CreateShipmentEventType:
		return c.handleCreateShipment(correlationID, payload)
	default:
		return nil
	}
}

func (c *ShipmentConsumer) handleCreateShipment(correlationID string, payload []byte) error {
	var message dto.CreateShipmentMessage
	if err := json.Unmarshal(payload, &message); err != nil {
		return err
	}

	address := dto.FromAddressMessage(message.Address)

	_, err := c.shipmentService.CreateShipment(context.Background(), correlationID, message.OrderID, address)
	return err
}
