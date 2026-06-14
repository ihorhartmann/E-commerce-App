package dto

import "inventoryservice/entities/model"

type CreateProductRequest struct {
	Name        string  `json:"name"`
	Description string  `json:"description"`
	Price       float64 `json:"price"`
	OnHand      uint    `json:"onHand"`
}

func (dto CreateProductRequest) ToDomainModel() (*model.Product, error) {
	return model.NewProduct(0, dto.Name, dto.Description, dto.Price, dto.OnHand, 0)
}
