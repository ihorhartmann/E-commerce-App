package kafka

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
