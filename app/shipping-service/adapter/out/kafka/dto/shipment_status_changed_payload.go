package dto

import "shippingservice/domain/model"

// ShipmentStatusChangedEvent is the shared payload shape for every shipment
// status Kafka event - the specific status is conveyed by the event's
// messageType header, not by a field in this payload.
type ShipmentStatusChangedEvent struct {
	OrderID        uint64 `json:"orderId"`
	TrackingNumber string `json:"trackingNumber"`
}

func ToShipmentStatusChangedEvent(shipment *model.Shipment) ShipmentStatusChangedEvent {
	return ShipmentStatusChangedEvent{
		OrderID:        shipment.OrderId(),
		TrackingNumber: shipment.TrackingNumber(),
	}
}
