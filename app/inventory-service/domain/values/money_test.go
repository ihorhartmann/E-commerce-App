package values

import (
	"errors"
	"testing"

	"inventoryservice/domain"
)

func TestNewMoney(t *testing.T) {
	t.Run("creates money with a non-negative amount and a supported currency", func(t *testing.T) {
		money, err := NewMoney(10.5, USD)
		if err != nil {
			t.Fatalf("unexpected error: %v", err)
		}
		if money.Amount() != 10.5 {
			t.Errorf("Amount() = %v, want 10.5", money.Amount())
		}
		if money.Currency() != USD {
			t.Errorf("Currency() = %v, want %v", money.Currency(), USD)
		}
	})

	t.Run("allows a zero amount", func(t *testing.T) {
		if _, err := NewMoney(0, USD); err != nil {
			t.Errorf("unexpected error: %v", err)
		}
	})

	t.Run("rejects a negative amount", func(t *testing.T) {
		_, err := NewMoney(-1, USD)
		if !errors.Is(err, domain.ErrInvalidInput) {
			t.Errorf("err = %v, want %v", err, domain.ErrInvalidInput)
		}
	})

	t.Run("rejects an unsupported currency", func(t *testing.T) {
		_, err := NewMoney(10, "JPY")
		if !errors.Is(err, domain.ErrInvalidInput) {
			t.Errorf("err = %v, want %v", err, domain.ErrInvalidInput)
		}
	})
}
