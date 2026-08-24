package product

import (
	"inventoryservice/domain/model"
)

type UpdateProductRequest struct {
	Name        string   `json:"name"`
	Description string   `json:"description"`
	Price       MoneyDTO `json:"price"`
	Quantity    uint     `json:"quantity"`
}

func FromUpdateProductRequest(id uint64, request UpdateProductRequest) (*model.Product, error) {
	price, err := fromMoneyDTO(request.Price)
	if err != nil {
		return nil, err
	}

	return model.NewExistingProduct(id, request.Name, request.Description, price, request.Quantity)
}
