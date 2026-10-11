package values

import (
	"inventoryservice/domain"

	"github.com/shopspring/decimal"
)

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

const minorUnitDigits = 2

type Money struct {
	amount   decimal.Decimal
	currency Currency
}

func NewMoney(amount decimal.Decimal, currency Currency) (Money, error) {
	wholeCents := amount.Round(minorUnitDigits)
	if amount.IsNegative() || !amount.Equal(wholeCents) || !currency.isValid() {
		return Money{}, domain.ErrInvalidInput
	}

	return Money{amount: wholeCents, currency: currency}, nil
}

func (m Money) Amount() decimal.Decimal {
	return m.amount
}

func (m Money) Currency() Currency {
	return m.currency
}

// Equal compares by value: a decimal can't be compared with ==.
func (m Money) Equal(other Money) bool {
	return m.amount.Equal(other.amount) && m.currency == other.currency
}
