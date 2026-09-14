package row

import (
	"inventoryservice/application/port/out/idempotency/dto"
	"time"
)

type Status string

const (
	InProgress Status = "IN_PROGRESS"
	Completed  Status = "COMPLETED"
)

type HTTPRequestRow struct {
	ID          string    `gorm:"primaryKey"`
	RequestHash string    `gorm:"not null"`
	Status      Status    `gorm:"not null"`
	HTTPCode    int       `gorm:"not null"`
	Response    []byte    `gorm:"type:jsonb"`
	CreatedAt   time.Time `gorm:"not null;index"`
	ClaimedAt   time.Time `gorm:"not null;index"`
	CompletedAt *time.Time
}

func (HTTPRequestRow) TableName() string {
	return "idempotent_http_requests"
}

func ToRecord(r *HTTPRequestRow) *dto.HTTPRequestRecord {
	return &dto.HTTPRequestRecord{
		ID:          r.ID,
		RequestHash: r.RequestHash,
		HTTPCode:    r.HTTPCode,
		Response:    r.Response,
		CreatedAt:   r.CreatedAt,
		ClaimedAt:   r.ClaimedAt,
		CompletedAt: r.CompletedAt,
	}
}
