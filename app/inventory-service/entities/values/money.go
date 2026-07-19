package values

import "inventoryservice/entities"

type Currency string

const (
	USD Currency = "USD"
	EUR Currency = "EUR"
	GBP Currency = "GBP"
)

func (c Currency) isValid() bool {
	switch c {
	case USD, EUR, GBP:
		return true
	default:
		return false
	}
}

type Money struct {
	amount   float64
	currency Currency
}

func NewMoney(amount float64, currency Currency) (Money, error) {
	if amount < 0 || !currency.isValid() {
		return Money{}, entities.ErrInvalidInput
	}

	return Money{amount: amount, currency: currency}, nil
}

func (m Money) Amount() float64 {
	return m.amount
}

func (m Money) Currency() Currency {
	return m.currency
}
