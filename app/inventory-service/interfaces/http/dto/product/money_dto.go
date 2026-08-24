package product

import "inventoryservice/domain/values"

type MoneyDTO struct {
	Amount   float64 `json:"amount"`
	Currency string  `json:"currency"`
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
