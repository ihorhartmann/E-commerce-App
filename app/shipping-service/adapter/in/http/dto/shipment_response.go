package dto

import (
	"shippingservice/domain/model"
	"shippingservice/domain/value"
	"time"
)

type ShipmentResponse struct {
	ID             uint64          `json:"id"`
	OrderID        uint64          `json:"orderId"`
	TrackingNumber string          `json:"trackingNumber"`
	Address        AddressResponse `json:"address"`
	Status         string          `json:"status"`
	CreatedAt      time.Time       `json:"createdAt"`
	UpdatedAt      time.Time       `json:"updatedAt"`
}

type AddressResponse struct {
	Country    string `json:"country"`
	City       string `json:"city"`
	PostalCode string `json:"postalCode"`
	Line1      string `json:"line1"`
	Line2      string `json:"line2"`
}

func ToShipmentResponse(shipment *model.Shipment) ShipmentResponse {
	return ShipmentResponse{
		ID:             shipment.Id(),
		OrderID:        shipment.OrderId(),
		TrackingNumber: shipment.TrackingNumber(),
		Address:        toAddressResponse(shipment.Address()),
		Status:         string(shipment.Status()),
		CreatedAt:      shipment.CreatedAt(),
		UpdatedAt:      shipment.UpdatedAt(),
	}
}

func toAddressResponse(address value.Address) AddressResponse {
	return AddressResponse{
		Country:    address.Country(),
		City:       address.City(),
		PostalCode: address.PostalCode(),
		Line1:      address.Line1(),
		Line2:      address.Line2(),
	}
}
