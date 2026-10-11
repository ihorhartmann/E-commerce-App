package reservation

import (
	"inventoryservice/domain/model"
	productdto "inventoryservice/interfaces/http/dto/product"
)

type CreateReservationResponse struct {
	Items []ReservedItemResponse `json:"items"`
}

type ReservedItemResponse struct {
	ProductID uint64              `json:"productId"`
	Price     productdto.MoneyDTO `json:"price"`
}

func ToCreateReservationResponse(products []*model.Product) CreateReservationResponse {
	items := make([]ReservedItemResponse, 0, len(products))
	for _, product := range products {
		items = append(items, toReservedItemResponse(product))
	}

	return CreateReservationResponse{Items: items}
}

func toReservedItemResponse(product *model.Product) ReservedItemResponse {
	return ReservedItemResponse{ProductID: product.ID(), Price: productdto.ToMoneyDTO(product.Price())}
}
