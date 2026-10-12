package dto

import "shippingservice/domain/model"

// ShipmentStatusChangedEvent is the shared payload shape for the shipment
// status Kafka events that carry nothing beyond which shipment they are about -
// the specific status is conveyed by the event's messageType header, not by a
// field in this payload.
type ShipmentStatusChangedEvent struct {
	OrderID    uint64 `json:"orderId"`
	ShipmentID uint64 `json:"shipmentId"`
}

func ToShipmentStatusChangedEvent(shipment *model.Shipment) ShipmentStatusChangedEvent {
	return ShipmentStatusChangedEvent{
		OrderID:    shipment.OrderId(),
		ShipmentID: shipment.Id(),
	}
}
