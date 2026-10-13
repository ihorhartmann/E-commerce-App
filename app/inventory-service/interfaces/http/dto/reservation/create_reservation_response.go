package reservation

import (
	"inventoryservice/application/dto"
	productdto "inventoryservice/interfaces/http/dto/product"
)

type CreateReservationResponse struct {
	Items []ReservedItemResponse `json:"items"`
}

type ReservedItemResponse struct {
	ProductID uint64              `json:"productId"`
	Name      string              `json:"name"`
	Quantity  uint                `json:"quantity"`
	Price     productdto.MoneyDTO `json:"price"`
}

func ToCreateReservationResponse(reservedItems []dto.ReservedItemDTO) CreateReservationResponse {
	items := make([]ReservedItemResponse, 0, len(reservedItems))
	for _, reservedItem := range reservedItems {
		items = append(items, toReservedItemResponse(reservedItem))
	}

	return CreateReservationResponse{Items: items}
}

func toReservedItemResponse(reservedItem dto.ReservedItemDTO) ReservedItemResponse {
	return ReservedItemResponse{
		ProductID: reservedItem.Product.ID(),
		Name:      reservedItem.Product.Name(),
		Quantity:  reservedItem.Quantity,
		Price:     productdto.ToMoneyDTO(reservedItem.Product.Price()),
	}
}
