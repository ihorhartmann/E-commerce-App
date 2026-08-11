package value

import (
	"errors"
	"testing"

	"shippingservice/domain"
)

func TestNewAddress(t *testing.T) {
	t.Run("creates an address with all fields set", func(t *testing.T) {
		address, err := NewAddress("Kyiv", "01001", "Khreshchatyk 1", "Apt 5")
		if err != nil {
			t.Fatalf("unexpected error: %v", err)
		}

		if address.City() != "Kyiv" {
			t.Errorf("City() = %q, want %q", address.City(), "Kyiv")
		}
		if address.PostalCode() != "01001" {
			t.Errorf("PostalCode() = %q, want %q", address.PostalCode(), "01001")
		}
		if address.Line1() != "Khreshchatyk 1" {
			t.Errorf("Line1() = %q, want %q", address.Line1(), "Khreshchatyk 1")
		}
		if address.Line2() != "Apt 5" {
			t.Errorf("Line2() = %q, want %q", address.Line2(), "Apt 5")
		}
	})

	t.Run("rejects a blank city", func(t *testing.T) {
		_, err := NewAddress("  ", "01001", "Khreshchatyk 1", "")
		if !errors.Is(err, domain.ErrInvalidInput) {
			t.Errorf("err = %v, want %v", err, domain.ErrInvalidInput)
		}
	})

	t.Run("rejects a blank postal code", func(t *testing.T) {
		_, err := NewAddress("Kyiv", "  ", "Khreshchatyk 1", "")
		if !errors.Is(err, domain.ErrInvalidInput) {
			t.Errorf("err = %v, want %v", err, domain.ErrInvalidInput)
		}
	})

	t.Run("rejects a blank line1", func(t *testing.T) {
		_, err := NewAddress("Kyiv", "01001", "  ", "")
		if !errors.Is(err, domain.ErrInvalidInput) {
			t.Errorf("err = %v, want %v", err, domain.ErrInvalidInput)
		}
	})

	t.Run("allows a blank line2", func(t *testing.T) {
		address, err := NewAddress("Kyiv", "01001", "Khreshchatyk 1", "")
		if err != nil {
			t.Fatalf("unexpected error: %v", err)
		}
		if address.Line2() != "" {
			t.Errorf("Line2() = %q, want empty", address.Line2())
		}
	})
}
