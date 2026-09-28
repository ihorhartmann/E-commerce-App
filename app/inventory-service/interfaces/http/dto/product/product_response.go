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

func ToProductResponse(stock *model.ProductStock) *ProductResponse {
	product := stock.Product()

	return &ProductResponse{
		ID:          product.ID(),
		Name:        product.Name(),
		Description: product.Description(),
		Price:       toMoneyDTO(product.Price()),
		Quantity:    product.Quantity(),
		Reserved:    stock.Reserved(),
		Available:   stock.Available(),
	}
}
