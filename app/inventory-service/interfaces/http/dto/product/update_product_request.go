package product

import (
	"inventoryservice/application/dto"
)

type UpdateProductRequest struct {
	Name        string   `json:"name"`
	Description string   `json:"description"`
	Price       MoneyDTO `json:"price"`
	Quantity    uint     `json:"quantity"`
}

// FromUpdateProductRequest takes the id separately: it comes from the URL path, not the request body.
func FromUpdateProductRequest(id uint64, request UpdateProductRequest) (dto.UpdateProductDTO, error) {
	price, err := fromMoneyDTO(request.Price)
	if err != nil {
		return dto.UpdateProductDTO{}, err
	}

	return dto.UpdateProductDTO{
		ID:          id,
		Name:        request.Name,
		Description: request.Description,
		Price:       price,
		Quantity:    request.Quantity,
	}, nil
}
