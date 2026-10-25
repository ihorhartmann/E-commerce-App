package values

import (
	"errors"
	"testing"

	"github.com/shopspring/decimal"
)

func mustMoney(t *testing.T, amount string, currency Currency) Money {
	t.Helper()
	money, err := NewMoney(decimal.RequireFromString(amount), currency)
	if err != nil {
		t.Fatalf("unexpected error building test money: %v", err)
	}
	return money
}

func TestNewMoney(t *testing.T) {
	t.Run("creates money with a non-negative amount and a supported currency", func(t *testing.T) {
		amount := decimal.RequireFromString("79.99")
		currency := EUR

		money, err := NewMoney(amount, currency)

		if err != nil {
			t.Fatalf("unexpected error: %v", err)
		}
		if !money.Amount().Equal(amount) {
			t.Errorf("Amount() = %v, want %v", money.Amount(), amount)
		}
		if money.Currency() != currency {
			t.Errorf("Currency() = %v, want %v", money.Currency(), currency)
		}
	})

	t.Run("keeps the amount with two decimal places, however it was written", func(t *testing.T) {
		for _, amount := range []string{"20", "20.0", "20.000"} {
			money, err := NewMoney(decimal.RequireFromString(amount), EUR)
			if err != nil {
				t.Fatalf("%s: unexpected error: %v", amount, err)
			}
			if got := money.Amount().Exponent(); got != -2 {
				t.Errorf("%s: Amount() has exponent %d, want -2", amount, got)
			}
		}
	})

	t.Run("allows a zero amount", func(t *testing.T) {
		if _, err := NewMoney(decimal.Zero, EUR); err != nil {
			t.Errorf("unexpected error: %v", err)
		}
	})

	t.Run("rejects a negative amount", func(t *testing.T) {
		_, err := NewMoney(decimal.NewFromInt(-1), EUR)

		if !errors.Is(err, ErrInvalidMoneyAmount) {
			t.Errorf("err = %v, want %v", err, ErrInvalidMoneyAmount)
		}
	})

	t.Run("rejects an unsupported currency", func(t *testing.T) {
		_, err := NewMoney(decimal.NewFromInt(10), "JPY")

		if !errors.Is(err, ErrUnsupportedCurrency) {
			t.Errorf("err = %v, want %v", err, ErrUnsupportedCurrency)
		}
	})

	t.Run("rejects an amount with a fraction of a cent", func(t *testing.T) {
		_, err := NewMoney(decimal.RequireFromString("9.999"), EUR)

		if !errors.Is(err, ErrInvalidMoneyAmount) {
			t.Errorf("err = %v, want %v", err, ErrInvalidMoneyAmount)
		}
	})
}

func TestMoney_Equal(t *testing.T) {
	t.Run("true for the same amount and currency, however the amount was written", func(t *testing.T) {
		if !mustMoney(t, "20", EUR).Equal(mustMoney(t, "20.00", EUR)) {
			t.Error("Equal() = false, want true")
		}
	})

	t.Run("false for a different amount", func(t *testing.T) {
		if mustMoney(t, "20", EUR).Equal(mustMoney(t, "20.01", EUR)) {
			t.Error("Equal() = true, want false")
		}
	})

	t.Run("false for a different currency", func(t *testing.T) {
		if mustMoney(t, "20", EUR).Equal(mustMoney(t, "20", USD)) {
			t.Error("Equal() = true, want false")
		}
	})
}
