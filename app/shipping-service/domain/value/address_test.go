package value

import (
	"errors"
	"testing"

	"shippingservice/domain"
)

func TestNewAddress(t *testing.T) {
	t.Run("creates an address with all fields set", func(t *testing.T) {
		city := "Kyiv"
		postalCode := "01001"
		line1 := "Khreshchatyk 1"
		line2 := "Apt 5"
		address, err := NewAddress(city, postalCode, line1, line2)
		if err != nil {
			t.Fatalf("unexpected error: %v", err)
		}

		if address.City() != city {
			t.Errorf("City() = %q, want %q", address.City(), city)
		}
		if address.PostalCode() != postalCode {
			t.Errorf("PostalCode() = %q, want %q", address.PostalCode(), postalCode)
		}
		if address.Line1() != line1 {
			t.Errorf("Line1() = %q, want %q", address.Line1(), line1)
		}
		if address.Line2() != line2 {
			t.Errorf("Line2() = %q, want %q", address.Line2(), line2)
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
