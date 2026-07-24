package reservation

import (
	"inventoryservice/application/dto"
	"inventoryservice/domain"
)

type ReservationItemRequest struct {
	ProductID uint64 `json:"productId"`
	Quantity  uint   `json:"quantity"`
}

type CreateReservationRequest struct {
	OrderID uint64                   `json:"orderId"`
	Items   []ReservationItemRequest `json:"items"`
}

func FromCreateReservationRequest(request CreateReservationRequest) ([]dto.ReservationDto, error) {
	if request.OrderID == 0 || len(request.Items) == 0 {
		return nil, domain.ErrInvalidInput
	}

	reservations := make([]dto.ReservationDto, 0, len(request.Items))
	for _, item := range request.Items {
		reservations = append(reservations, dto.ReservationDto{ProductID: item.ProductID, Quantity: item.Quantity})
	}

	return reservations, nil
}
