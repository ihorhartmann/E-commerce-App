package kafka

import (
	"fmt"
	"shippingservice/domain/model"
)

type MessageType string

func (mt MessageType) String() string {
	return string(mt)
}

const (
	CreateShipmentCommand MessageType = "CreateShipment"

	ShipmentCreationFailedEvent      MessageType = "ShipmentCreationFailed"
	ShipmentCreatedEvent             MessageType = "ShipmentCreated"
	ShipmentInDeliveryEvent          MessageType = "ShipmentInDelivery"
	ShipmentDeliveredEvent           MessageType = "ShipmentDelivered"
	ShipmentCancelledEvent           MessageType = "ShipmentCancelled"
	ShipmentLostEvent                MessageType = "ShipmentLost"
	ShipmentReturnedToInventoryEvent MessageType = "ShipmentReturnedToInventory"
)

var shipmentStatusMessageTypes = map[model.ShipmentStatus]MessageType{
	model.Created:    ShipmentCreatedEvent,
	model.InDelivery: ShipmentInDeliveryEvent,
	model.Delivered:  ShipmentDeliveredEvent,
	model.Cancelled:  ShipmentCancelledEvent,
	model.Lost:       ShipmentLostEvent,
}

func ConvertShipmentStatusMessageType(status model.ShipmentStatus) (MessageType, error) {
	messageType, ok := shipmentStatusMessageTypes[status]
	if !ok {
		return "", fmt.Errorf("no kafka message type mapped for shipment status %q", status)
	}

	return messageType, nil
}
