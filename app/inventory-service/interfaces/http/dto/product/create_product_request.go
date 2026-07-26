package product

import (
	"inventoryservice/domain/model"
)

type CreateProductRequest struct {
	Name        string   `json:"name"`
	Description string   `json:"description"`
	Price       MoneyDTO `json:"price"`
	Quantity    uint     `json:"quantity"`
}

func FromCreateProductRequest(request CreateProductRequest) (*model.Product, error) {
	price, err := fromMoneyDTO(request.Price)
	if err != nil {
		return nil, err
	}

	return model.NewProduct(0, request.Name, request.Description, price, request.Quantity)
}
