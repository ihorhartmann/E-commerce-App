package dto

import "inventoryservice/domain/model"

type ReservationDTO struct {
	ProductID uint64
	Quantity  uint
}

// ReservedItemDTO is one reserved line: the product as stored once its stock was reserved, and how much of it.
type ReservedItemDTO struct {
	Product  *model.Product
	Quantity uint
}
