package repository

import (
	"context"
	"fmt"
	"shippingservice/application/port/out/repository"
	"shippingservice/domain/model"
	"shippingservice/domain/value"
	"strings"
	"sync"
	"time"

	"github.com/google/uuid"
)

type InMemoryShipmentRepository struct {
	repository.ShipmentRepository
	mu               sync.RWMutex
	shipments        map[uint64]*model.Shipment
	byTrackingNumber map[string]uint64
	nextID           uint64
}

func NewInMemoryShipmentRepository() repository.ShipmentRepository {
	now := time.Date(2026, time.July, 11, 10, 0, 0, 0, time.UTC)

	defaultShipments := []*model.Shipment{
		checkShipment(model.NewExistingShipment(
			1,
			101,
			"TRK-1001",
			checkAddress(value.NewAddress("Kyiv", "01001", "Khreshchatyk St 1", "Apt 12")),
			model.Delivered,
			model.HolderCourier,
			now.Add(-96*time.Hour),
			now.Add(-72*time.Hour),
		)),
		checkShipment(model.NewExistingShipment(
			2,
			102,
			"TRK-1002",
			checkAddress(value.NewAddress("Lviv", "79000", "Rynok Square 5", "")),
			model.Delivered,
			model.HolderCourier,
			now.Add(-72*time.Hour),
			now.Add(-48*time.Hour),
		)),
		checkShipment(model.NewExistingShipment(
			3,
			103,
			"TRK-1003",
			checkAddress(value.NewAddress("Warsaw", "00-001", "Marszalkowska 10", "Floor 3")),
			model.Cancelled,
			model.HolderInventory,
			now.Add(-48*time.Hour),
			now.Add(-24*time.Hour),
		)),
		checkShipment(model.NewExistingShipment(
			4,
			104,
			"TRK-1004",
			checkAddress(value.NewAddress("Berlin", "10115", "Invalidenstrasse 20", "")),
			model.Delivered,
			model.HolderCourier,
			now.Add(-24*time.Hour),
			now,
		)),
	}

	shipments := make(map[uint64]*model.Shipment, len(defaultShipments))
	byTrackingNumber := make(map[string]uint64, len(defaultShipments))
	for _, shipment := range defaultShipments {
		shipments[shipment.Id()] = shipment
		byTrackingNumber[shipment.TrackingNumber()] = shipment.Id()
	}

	return &InMemoryShipmentRepository{
		shipments:        shipments,
		byTrackingNumber: byTrackingNumber,
		nextID:           uint64(len(defaultShipments) + 1),
	}
}

func (r *InMemoryShipmentRepository) Create(_ context.Context, orderID uint64, address value.Address) (*model.Shipment, error) {
	r.mu.Lock()
	defer r.mu.Unlock()

	trackingNumber := fmt.Sprintf("%d%s", orderID, strings.ToUpper(uuid.New().String()[:8]))
	shipment, err := model.NewShipment(orderID, trackingNumber, address)
	if err != nil {
		return nil, err
	}

	created, err := model.NewExistingShipment(
		r.nextID,
		shipment.OrderId(),
		shipment.TrackingNumber(),
		shipment.Address(),
		shipment.Status(),
		shipment.Holder(),
		shipment.CreatedAt(),
		shipment.UpdatedAt(),
	)
	if err != nil {
		return nil, err
	}

	r.shipments[created.Id()] = created
	r.byTrackingNumber[created.TrackingNumber()] = created.Id()
	r.nextID++

	return created, nil
}

func (r *InMemoryShipmentRepository) GetByID(_ context.Context, id uint64) (*model.Shipment, error) {
	r.mu.RLock()
	defer r.mu.RUnlock()

	shipment, ok := r.shipments[id]
	if !ok {
		return nil, repository.ErrShipmentNotFound
	}

	return shipment, nil
}

func (r *InMemoryShipmentRepository) GetByTrackingNumber(_ context.Context, trackingNumber string) (*model.Shipment, error) {
	r.mu.RLock()
	defer r.mu.RUnlock()

	id, ok := r.byTrackingNumber[trackingNumber]
	if !ok {
		return nil, repository.ErrShipmentNotFound
	}

	return r.shipments[id], nil
}

func (r *InMemoryShipmentRepository) Update(_ context.Context, shipment *model.Shipment) (*model.Shipment, error) {
	r.mu.Lock()
	defer r.mu.Unlock()

	if _, ok := r.shipments[shipment.Id()]; !ok {
		return nil, repository.ErrShipmentNotFound
	}

	r.shipments[shipment.Id()] = shipment
	return shipment, nil
}

func checkShipment(shipment *model.Shipment, err error) *model.Shipment {
	if err != nil {
		panic(err)
	}

	return shipment
}

func checkAddress(address value.Address, err error) value.Address {
	if err != nil {
		panic(err)
	}

	return address
}
