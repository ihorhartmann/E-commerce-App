package dto

import (
	"shippingservice/domain/value"

	"github.com/google/uuid"
)

type AddressDTO struct {
	City       string `json:"city"`
	PostalCode string `json:"postalCode"`
	Line1      string `json:"line1"`
	Line2      string `json:"line2"`
}

type CreateShipmentCommand struct {
	OrderID    uint64     `json:"orderId"`
	CustomerID uuid.UUID  `json:"customerId"`
	Address    AddressDTO `json:"address"`
}

func FromAddressDTO(message AddressDTO) (value.Address, error) {
	return value.NewAddress(message.City, message.PostalCode, message.Line1, message.Line2)
}
