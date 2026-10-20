package kafka

import (
	"log/slog"
	"shippingservice/domain/model"
)

type MessageType string

func (mt MessageType) String() string {
	return string(mt)
}

const (
	CreateShipmentCommand MessageType = "CreateShipment"
	CancelShipmentCommand MessageType = "CancelShipment"

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
		return "", ErrUnmappedShipmentStatus.WithAttrs(slog.String("status", string(status)))
	}

	return messageType, nil
}
