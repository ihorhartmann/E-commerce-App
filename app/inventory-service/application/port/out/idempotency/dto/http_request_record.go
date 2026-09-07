package dto

import "time"

type HTTPRequestRecord struct {
	ID          string
	RequestHash string
	HTTPCode    int
	Response    []byte
	CreatedAt   time.Time
	ClaimedAt   time.Time
	CompletedAt *time.Time
}
