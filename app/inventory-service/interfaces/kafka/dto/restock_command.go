package dto

import applicationdto "inventoryservice/application/dto"

type RestockItem struct {
	ProductID uint64 `json:"productId"`
	Quantity  uint   `json:"quantity"`
}

type RestockCommand struct {
	OrderID uint64        `json:"orderId"`
	Items   []RestockItem `json:"items"`
}

func ToReservationDtos(items []RestockItem) []applicationdto.ReservationDto {
	reservationDtos := make([]applicationdto.ReservationDto, 0, len(items))
	for _, item := range items {
		reservationDtos = append(reservationDtos, applicationdto.ReservationDto{ProductID: item.ProductID, Quantity: item.Quantity})
	}

	return reservationDtos
}
