package dto

type ShipmentCreationFailedDto struct {
	OrderID uint64 `json:"orderId"`
}

func ToShipmentCreationFailedDto(orderID uint64) ShipmentCreationFailedDto {
	return ShipmentCreationFailedDto{OrderID: orderID}
}
