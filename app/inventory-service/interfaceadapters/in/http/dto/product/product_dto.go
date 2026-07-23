package product

import (
	"inventoryservice/entities/model"
	"inventoryservice/entities/values"
)

type MoneyDTO struct {
	Amount   float64 `json:"amount"`
	Currency string  `json:"currency"`
}

type ProductResponse struct {
	ID          uint64   `json:"id"`
	Name        string   `json:"name"`
	Description string   `json:"description"`
	Price       MoneyDTO `json:"price"`
	Quantity    uint     `json:"quantity"`
	Reserved    uint     `json:"reserved"`
	Available   uint     `json:"available"`
}

func ToProductResponse(product *model.Product) *ProductResponse {
	return &ProductResponse{
		ID:          product.ID(),
		Name:        product.Name(),
		Description: product.Description(),
		Price:       toMoneyDTO(product.Price()),
		Quantity:    product.Quantity(),
		Reserved:    product.Reserved(),
		Available:   product.Available(),
	}
}

func toMoneyDTO(money values.Money) MoneyDTO {
	return MoneyDTO{
		Amount:   money.Amount(),
		Currency: string(money.Currency()),
	}
}

func fromMoneyDTO(money MoneyDTO) (values.Money, error) {
	return values.NewMoney(money.Amount, values.Currency(money.Currency))
}
