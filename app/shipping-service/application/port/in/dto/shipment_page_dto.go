package dto

import "shippingservice/domain/model"

// ShipmentPageDTO is one page of shipments, with the number of shipments across all pages.
type ShipmentPageDTO struct {
	Shipments     []*model.Shipment
	TotalElements int64
}
