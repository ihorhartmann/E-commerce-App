package kafka

type EventType string

func (et EventType) String() string {
	return string(et)
}

const (
	CreateShipmentEventType         EventType = "CreateShipment"
	ShipmentCreationFailedEventType EventType = "ShipmentCreationFailed"

	ShipmentCreatedEventType             EventType = "ShipmentCreated"
	ShipmentInDeliveryEventType          EventType = "ShipmentInDelivery"
	ShipmentDeliveredEventType           EventType = "ShipmentDelivered"
	ShipmentCancelledEventType           EventType = "ShipmentCancelled"
	ShipmentLostEventType                EventType = "ShipmentLost"
	ShipmentReturnedToInventoryEventType EventType = "ShipmentReturnedToInventory"
)
