package dto

import "shippingservice/application/port/in/dto"

// ShipmentListResponse is one page of a courier's shipment list.
type ShipmentListResponse struct {
	Items         []ShipmentResponse `json:"items"`
	Page          int                `json:"page"`
	Size          int                `json:"size"`
	TotalElements int64              `json:"totalElements"`
	TotalPages    int64              `json:"totalPages"`
}

// ToShipmentListResponse shows the street of every shipment only with fullAddress.
func ToShipmentListResponse(shipmentPage *dto.ShipmentPageDTO, page, size int, fullAddress bool) ShipmentListResponse {
	items := make([]ShipmentResponse, 0, len(shipmentPage.Shipments))
	for _, shipment := range shipmentPage.Shipments {
		items = append(items, ToShipmentResponse(shipment, fullAddress))
	}

	return ShipmentListResponse{
		Items:         items,
		Page:          page,
		Size:          size,
		TotalElements: shipmentPage.TotalElements,
		TotalPages:    (shipmentPage.TotalElements + int64(size) - 1) / int64(size),
	}
}
