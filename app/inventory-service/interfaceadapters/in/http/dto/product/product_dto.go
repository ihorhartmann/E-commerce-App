package product

import "inventoryservice/entities/model"

type MoneyDTO struct {
	Amount   float64 `json:"amount"`
	Currency string  `json:"currency"`
}

type ProductDTO struct {
	ID          uint64   `json:"id"`
	Name        string   `json:"name"`
	Description string   `json:"description"`
	Price       MoneyDTO `json:"price"`
	Quantity    uint     `json:"quantity"`
	Reserved    uint     `json:"reserved"`
	Available   uint     `json:"available"`
}

func ToProductResponse(product *model.Product) *ProductDTO {
	return &ProductDTO{
		ID:          product.ID(),
		Name:        product.Name(),
		Description: product.Description(),
		Price:       MoneyDTO{Amount: product.Price().Amount(), Currency: string(product.Price().Currency())},
		Quantity:    product.Quantity(),
		Reserved:    product.Reserved(),
		Available:   product.Available(),
	}
}
