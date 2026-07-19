package dto

import "shippingservice/domain/value"

type AddressMessage struct {
	City       string `json:"city"`
	PostalCode string `json:"postalCode"`
	Line1      string `json:"line1"`
	Line2      string `json:"line2"`
}

type CreateShipmentMessage struct {
	OrderID uint64         `json:"orderId"`
	Address AddressMessage `json:"address"`
}

func FromAddressMessage(message AddressMessage) value.Address {
	return value.NewAddress(message.City, message.PostalCode, message.Line1, message.Line2)
}
