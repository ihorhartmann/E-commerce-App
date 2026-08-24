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

func ToReservationDTOs(items []RestockItem) []applicationdto.ReservationDTO {
	reservationDTOs := make([]applicationdto.ReservationDTO, 0, len(items))
	for _, item := range items {
		reservationDTOs = append(reservationDTOs, applicationdto.ReservationDTO{ProductID: item.ProductID, Quantity: item.Quantity})
	}

	return reservationDTOs
}
