package reservation

import (
	"log/slog"

	"inventoryservice/application/dto"
)

type ReservationItemRequest struct {
	ProductID uint64 `json:"productId"`
	Quantity  uint   `json:"quantity"`
}

type CreateReservationRequest struct {
	OrderID uint64                   `json:"orderId"`
	Items   []ReservationItemRequest `json:"items"`
}

func FromCreateReservationRequest(request CreateReservationRequest) ([]dto.ReservationDTO, error) {
	if request.OrderID == 0 {
		return nil, ErrInvalidReservationRequest.WithExternalMessage("orderId must be positive")
	}
	if len(request.Items) == 0 {
		return nil, ErrInvalidReservationRequest.
			WithAttrs(slog.Uint64("orderID", request.OrderID)).
			WithExternalMessage("items must not be empty")
	}

	reservations := make([]dto.ReservationDTO, 0, len(request.Items))
	for _, item := range request.Items {
		reservations = append(reservations, dto.ReservationDTO{ProductID: item.ProductID, Quantity: item.Quantity})
	}

	return reservations, nil
}
