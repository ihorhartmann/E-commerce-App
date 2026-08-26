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
	Holder         string          `json:"holder"`
	CreatedAt      time.Time       `json:"createdAt"`
	UpdatedAt      *time.Time      `json:"updatedAt"`
}

type AddressResponse struct {
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
		Holder:         string(shipment.Holder()),
		CreatedAt:      shipment.CreatedAt(),
		UpdatedAt:      toResponseTime(shipment.UpdatedAt()),
	}
}

func toResponseTime(t time.Time) *time.Time {
	if t.IsZero() {
		return nil
	}
	return &t
}

func toAddressResponse(address value.Address) AddressResponse {
	return AddressResponse{
		City:       address.City(),
		PostalCode: address.PostalCode(),
		Line1:      address.Line1(),
		Line2:      address.Line2(),
	}
}
