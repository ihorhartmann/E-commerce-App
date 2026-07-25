package dto

import "shippingservice/domain/value"

type AddressDto struct {
	City       string `json:"city"`
	PostalCode string `json:"postalCode"`
	Line1      string `json:"line1"`
	Line2      string `json:"line2"`
}

type CreateShipmentCommand struct {
	OrderID uint64     `json:"orderId"`
	Address AddressDto `json:"address"`
}

func FromAddressMessage(message AddressDto) (value.Address, error) {
	return value.NewAddress(message.City, message.PostalCode, message.Line1, message.Line2)
}
