package dto

import "inventoryservice/entities/model"

type CreateProductRequest struct {
	Name        string  `json:"name"`
	Description string  `json:"description"`
	Price       float64 `json:"price"`
	OnHand      uint    `json:"onHand"`
}

func FromCreateProductRequest(request CreateProductRequest) (*model.Product, error) {
	return model.NewProduct(0, request.Name, request.Description, request.Price, request.OnHand, 0)
}
