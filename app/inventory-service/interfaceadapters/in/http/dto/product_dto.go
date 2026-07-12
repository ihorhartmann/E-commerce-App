package dto

import "inventoryservice/entities/model"

type ProductDTO struct {
	ID          uint64  `json:"id"`
	Name        string  `json:"name"`
	Description string  `json:"description"`
	Price       float64 `json:"price"`
	OnHand      uint    `json:"onHand"`
	Reserved    uint    `json:"reserved"`
	Available   uint    `json:"available"`
}

func ToProductResponse(product *model.Product) *ProductDTO {
	return &ProductDTO{
		ID:          product.ID(),
		Name:        product.Name(),
		Description: product.Description(),
		Price:       product.Price(),
		OnHand:      product.OnHand(),
		Reserved:    product.Reserved(),
		Available:   product.Available(),
	}
}
