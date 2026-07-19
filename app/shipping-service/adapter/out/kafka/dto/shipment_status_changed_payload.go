package dto

import "shippingservice/domain/model"

// ShipmentStatusChangedDto is the shared payload shape for every shipment
// status Kafka event - the specific status is conveyed by the event's
// eventType header, not by a field in this payload.
type ShipmentStatusChangedDto struct {
	OrderID        uint64 `json:"orderId"`
	TrackingNumber string `json:"trackingNumber"`
}

func ToShipmentStatusChangedDto(shipment *model.Shipment) ShipmentStatusChangedDto {
	return ShipmentStatusChangedDto{
		OrderID:        shipment.OrderId(),
		TrackingNumber: shipment.TrackingNumber(),
	}
}
