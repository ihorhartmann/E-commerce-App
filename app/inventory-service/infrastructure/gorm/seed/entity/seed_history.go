package entity

import "time"

type SeedHistory struct {
	Name      string    `gorm:"primaryKey"`
	AppliedAt time.Time `gorm:"type:timestamptz;not null;default:now()"`
}

func (SeedHistory) TableName() string {
	return "seed_history"
}
