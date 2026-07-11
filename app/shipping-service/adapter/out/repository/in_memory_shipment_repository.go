package repository

import (
	"context"
	"shippingservice/application/port/out/repository"
	"shippingservice/domain/model"
	"shippingservice/domain/value"
	"sync"
	"time"
)

type InMemoryShipmentRepository struct {
	repository.ShipmentRepository
	mu        sync.RWMutex
	shipments map[string]*model.Shipment
}

func NewInMemoryShipmentRepository() repository.ShipmentRepository {
	now := time.Date(2026, time.July, 11, 10, 0, 0, 0, time.UTC)

	defaultShipments := []*model.Shipment{
		model.NewExistingShipment(
			1,
			101,
			"TRK-1001",
			value.NewAddress("Ukraine", "Kyiv", "01001", "Khreshchatyk St 1", "Apt 12"),
			model.Delivered,
			now.Add(-96*time.Hour),
			now.Add(-72*time.Hour),
		),
		model.NewExistingShipment(
			2,
			102,
			"TRK-1002",
			value.NewAddress("Ukraine", "Lviv", "79000", "Rynok Square 5", ""),
			model.Delivered,
			now.Add(-72*time.Hour),
			now.Add(-48*time.Hour),
		),
		model.NewExistingShipment(
			3,
			103,
			"TRK-1003",
			value.NewAddress("Poland", "Warsaw", "00-001", "Marszalkowska 10", "Floor 3"),
			model.Cancelled,
			now.Add(-48*time.Hour),
			now.Add(-24*time.Hour),
		),
		model.NewExistingShipment(
			4,
			104,
			"TRK-1004",
			value.NewAddress("Germany", "Berlin", "10115", "Invalidenstrasse 20", ""),
			model.Delivered,
			now.Add(-24*time.Hour),
			now,
		),
	}

	shipments := make(map[string]*model.Shipment, len(defaultShipments))
	for _, shipment := range defaultShipments {
		shipments[shipment.TrackingNumber()] = shipment
	}

	return &InMemoryShipmentRepository{shipments: shipments}
}

func (r *InMemoryShipmentRepository) GetByTrackingNumber(_ context.Context, trackingNumber string) (*model.Shipment, error) {
	r.mu.RLock()
	defer r.mu.RUnlock()

	shipment, ok := r.shipments[trackingNumber]
	if !ok {
		return nil, repository.ErrShipmentNotFound
	}

	return shipment, nil
}
