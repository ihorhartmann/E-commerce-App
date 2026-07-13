package value

type Address struct {
	country    string
	city       string
	postalCode string
	line1      string
	line2      string
}

func NewAddress(country, city, postalCode, line1, line2 string) Address {
	return Address{
		country:    country,
		city:       city,
		postalCode: postalCode,
		line1:      line1,
		line2:      line2,
	}
}

func (a Address) Country() string {
	return a.country
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
