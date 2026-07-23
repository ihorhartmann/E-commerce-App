package dto

type ShipmentCreationFailedEvent struct {
	OrderID uint64 `json:"orderId"`
}

func ToShipmentCreationFailedEvent(orderID uint64) ShipmentCreationFailedEvent {
	return ShipmentCreationFailedEvent{OrderID: orderID}
}
