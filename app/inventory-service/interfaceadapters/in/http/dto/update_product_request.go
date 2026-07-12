package dto

import "inventoryservice/entities/model"

type UpdateProductRequest struct {
	Name        string  `json:"name"`
	Description string  `json:"description"`
	Price       float64 `json:"price"`
	OnHand      uint    `json:"onHand"`
	Reserved    uint    `json:"reserved"`
}

func FromUpdateProductRequest(id uint64, request UpdateProductRequest) (*model.Product, error) {
	return model.NewExistingProduct(id, request.Name, request.Description, request.Price, request.OnHand, request.Reserved)
}
