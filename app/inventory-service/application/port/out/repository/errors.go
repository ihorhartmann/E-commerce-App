package repository

import "errors"

var (
	ErrProductNotFound     = errors.New("product not found")
	ErrReservationNotFound = errors.New("reservation not found")
)
