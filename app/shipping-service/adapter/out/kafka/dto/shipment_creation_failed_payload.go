package dto

type ShipmentCreationFailedEvent struct {
	OrderID uint64 `json:"orderId"`
}
