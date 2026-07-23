package dto

type CancelReservationCommand struct {
	OrderID uint64 `json:"orderId"`
}
