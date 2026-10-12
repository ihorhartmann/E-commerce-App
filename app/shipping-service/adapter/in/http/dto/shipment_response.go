package dto

import (
	"shippingservice/domain/model"
	"shippingservice/domain/value"
	"time"
)

type ShipmentResponse struct {
	ID                 uint64          `json:"id"`
	OrderID            uint64          `json:"orderId"`
	Address            AddressResponse `json:"address"`
	Status             string          `json:"status"`
	Holder             string          `json:"holder"`
	CancellationReason string          `json:"cancellationReason,omitempty"`
	CreatedAt          time.Time       `json:"createdAt"`
	UpdatedAt          *time.Time      `json:"updatedAt"`
}

// AddressResponse leaves the street out for a courier the shipment isn't assigned to: city and postal code are enough
// to decide whether to pick it up.
type AddressResponse struct {
	City       string `json:"city"`
	PostalCode string `json:"postalCode"`
	Line1      string `json:"line1,omitempty"`
	Line2      string `json:"line2,omitempty"`
}

// ToShipmentResponse shows the street only with fullAddress.
func ToShipmentResponse(shipment *model.Shipment, fullAddress bool) ShipmentResponse {
	return ShipmentResponse{
		ID:                 shipment.Id(),
		OrderID:            shipment.OrderId(),
		Address:            toAddressResponse(shipment.Address(), fullAddress),
		Status:             string(shipment.Status()),
		Holder:             string(shipment.Holder()),
		CancellationReason: string(shipment.CancellationReason()),
		CreatedAt:          shipment.CreatedAt(),
		UpdatedAt:          toResponseTime(shipment.UpdatedAt()),
	}
}

func toResponseTime(t time.Time) *time.Time {
	if t.IsZero() {
		return nil
	}
	return &t
}

func toAddressResponse(address value.Address, fullAddress bool) AddressResponse {
	response := AddressResponse{
		City:       address.City(),
		PostalCode: address.PostalCode(),
	}
	if fullAddress {
		response.Line1 = address.Line1()
		response.Line2 = address.Line2()
	}
	return response
}
