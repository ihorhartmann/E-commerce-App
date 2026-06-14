package dto

type CancelReservationMessage struct {
	ProductID uint64 `json:"productId"`
	OrderID   uint64 `json:"orderId"`
}
