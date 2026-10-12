package dto

import "shippingservice/domain/model"

type ShipmentCancelledEvent struct {
	OrderID    uint64 `json:"orderId"`
	ShipmentID uint64 `json:"shipmentId"`
	Reason     string `json:"reason"`
}

func ToShipmentCancelledEvent(shipment *model.Shipment) ShipmentCancelledEvent {
	return ShipmentCancelledEvent{
		OrderID:    shipment.OrderId(),
		ShipmentID: shipment.Id(),
		Reason:     string(shipment.CancellationReason()),
	}
}
