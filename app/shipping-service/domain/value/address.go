package value

import "strings"

type Address struct {
	city       string
	postalCode string
	line1      string
	line2      string
}

func NewAddress(city, postalCode, line1, line2 string) (Address, error) {
	if strings.TrimSpace(city) == "" || strings.TrimSpace(postalCode) == "" || strings.TrimSpace(line1) == "" {
		return Address{}, ErrInvalidAddress
	}

	return Address{
		city:       city,
		postalCode: postalCode,
		line1:      line1,
		line2:      line2,
	}, nil
}

func (a Address) City() string {
	return a.city
}

func (a Address) PostalCode() string {
	return a.postalCode
}

func (a Address) Line1() string {
	return a.line1
}

func (a Address) Line2() string {
	return a.line2
}
