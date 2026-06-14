package dto

import "inventoryservice/entities/model"

type UpdateProductRequest struct {
	Name        string  `json:"name"`
	Description string  `json:"description"`
	Price       float64 `json:"price"`
	OnHand      uint    `json:"onHand"`
	Reserved    uint    `json:"reserved"`
}

func (dto UpdateProductRequest) ToDomainModel(id uint64) (*model.Product, error) {
	return model.NewExistingProduct(id, dto.Name, dto.Description, dto.Price, dto.OnHand, dto.Reserved)
}
