package product

import (
	"encoding/json"

	"inventoryservice/domain"
	"inventoryservice/domain/values"

	"github.com/shopspring/decimal"
)

type MoneyDTO struct {
	Amount   json.Number `json:"amount"`
	Currency string      `json:"currency"`
}

func ToMoneyDTO(money values.Money) MoneyDTO {
	return MoneyDTO{
		Amount:   json.Number(money.Amount().String()),
		Currency: string(money.Currency()),
	}
}

func fromMoneyDTO(money MoneyDTO) (values.Money, error) {
	amount, err := decimal.NewFromString(money.Amount.String())
	if err != nil {
		return values.Money{}, domain.ErrInvalidInput
	}

	return values.NewMoney(amount, values.Currency(money.Currency))
}
