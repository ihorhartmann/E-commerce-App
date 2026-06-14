package dto

type CreateReservationMessage struct {
	ID        uint64 `json:"id"`
	ProductID uint64 `json:"productId"`
	OrderID   uint64 `json:"orderId"`
	Quantity  uint   `json:"quantity"`
}
