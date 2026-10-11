package product

import (
	"inventoryservice/domain/model"
)

type ProductResponse struct {
	ID          uint64   `json:"id"`
	Name        string   `json:"name"`
	Description string   `json:"description"`
	Price       MoneyDTO `json:"price"`
	Quantity    uint     `json:"quantity"`
	Reserved    uint     `json:"reserved"`
	Available   uint     `json:"available"`
}

func ToProductResponse(product *model.Product) *ProductResponse {
	return &ProductResponse{
		ID:          product.ID(),
		Name:        product.Name(),
		Description: product.Description(),
		Price:       ToMoneyDTO(product.Price()),
		Quantity:    product.Quantity(),
		Reserved:    product.Reserved(),
		Available:   product.Available(),
	}
}
