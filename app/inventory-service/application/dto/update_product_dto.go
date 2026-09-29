package dto

import "inventoryservice/domain/values"

// UpdateProductDTO identifies the product to update and carries the details the update replaces. The
// reserved quantity isn't one of them - only reservations change it.
type UpdateProductDTO struct {
	ID          uint64
	Name        string
	Description string
	Price       values.Money
	Quantity    uint
}
