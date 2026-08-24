package service

import (
	"context"
	"errors"
	"reflect"
	"testing"
	"time"

	"go.uber.org/mock/gomock"

	kafkamocks "shippingservice/application/port/out/kafka/mocks"
	repomocks "shippingservice/application/port/out/repository/mocks"
	"shippingservice/domain/model"
	"shippingservice/domain/value"
)

func testAddress(t *testing.T) value.Address {
	t.Helper()
	address, err := value.NewAddress("Kyiv", "01001", "Khreshchatyk 1", "")
	if err != nil {
		t.Fatalf("unexpected error building test address: %v", err)
	}
	return address
}

func testShipment(t *testing.T, id uint64, status model.ShipmentStatus, holder model.ShipmentHolder) *model.Shipment {
	t.Helper()
	now := time.Now()
	shipment, err := model.NewExistingShipment(id, 100, "TRACK-1", testAddress(t), status, holder, now, now)
	if err != nil {
		t.Fatalf("unexpected error building test shipment: %v", err)
	}
	return shipment
}

func TestShipmentServiceImpl_GetByTrackingNumber(t *testing.T) {
	t.Run("returns the shipment from the repository", func(t *testing.T) {
		trackingNumber := "TRACK-1"
		shipment := testShipment(t, 1, model.Created, model.HolderInventory)
		shipmentRepo := repomocks.NewMockShipmentRepository(gomock.NewController(t))
		shipmentRepo.EXPECT().GetByTrackingNumber(gomock.Any(), trackingNumber).Return(shipment, nil).Times(1)

		service := NewShipmentServiceImpl(shipmentRepo, kafkamocks.NewMockShipmentKafkaProducer(gomock.NewController(t)))

		got, err := service.GetByTrackingNumber(context.Background(), trackingNumber)

		if err != nil {
			t.Fatalf("unexpected error: %v", err)
		}
		if !reflect.DeepEqual(got, shipment) {
			t.Errorf("GetByTrackingNumber() = %+v, want %+v", got, shipment)
		}
	})

	t.Run("rejects a blank tracking number without calling the repository", func(t *testing.T) {
		shipmentRepo := repomocks.NewMockShipmentRepository(gomock.NewController(t))
		shipmentRepo.EXPECT().GetByTrackingNumber(gomock.Any(), gomock.Any()).Times(0)

		service := NewShipmentServiceImpl(shipmentRepo, kafkamocks.NewMockShipmentKafkaProducer(gomock.NewController(t)))

		_, err := service.GetByTrackingNumber(context.Background(), "   ")

		if !errors.Is(err, ErrInvalidTrackingNumber) {
			t.Errorf("err = %v, want %v", err, ErrInvalidTrackingNumber)
		}
	})
}

func TestShipmentServiceImpl_CreateShipment(t *testing.T) {
	t.Run("persists a new shipment via the repository, then publishes the status change", func(t *testing.T) {
		orderID := uint64(100)
		correlationID := "corr-1"
		address := testAddress(t)
		persisted := testShipment(t, 1, model.Created, model.HolderInventory)
		shipmentRepo := repomocks.NewMockShipmentRepository(gomock.NewController(t))
		producer := kafkamocks.NewMockShipmentKafkaProducer(gomock.NewController(t))

		shipmentRepo.EXPECT().Create(gomock.Any(), orderID, address).Return(persisted, nil).Times(1)
		producer.EXPECT().PublishShipmentStatusChanged(gomock.Any(), correlationID, persisted).Return(nil).Times(1)

		service := NewShipmentServiceImpl(shipmentRepo, producer)

		got, err := service.CreateShipment(context.Background(), correlationID, orderID, address)

		if err != nil {
			t.Fatalf("unexpected error: %v", err)
		}
		if !reflect.DeepEqual(got, persisted) {
			t.Errorf("CreateShipment() = %+v, want %+v", got, persisted)
		}
	})

	t.Run("publishes creation-failed and returns the original error when persisting fails", func(t *testing.T) {
		orderID := uint64(100)
		correlationID := "corr-1"
		address := testAddress(t)
		wantErr := errors.New("db down")
		shipmentRepo := repomocks.NewMockShipmentRepository(gomock.NewController(t))
		producer := kafkamocks.NewMockShipmentKafkaProducer(gomock.NewController(t))

		shipmentRepo.EXPECT().Create(gomock.Any(), orderID, address).Return(nil, wantErr).Times(1)
		producer.EXPECT().PublishShipmentCreationFailed(gomock.Any(), correlationID, orderID).Return(nil).Times(1)
		producer.EXPECT().PublishShipmentStatusChanged(gomock.Any(), gomock.Any(), gomock.Any()).Times(0)

		service := NewShipmentServiceImpl(shipmentRepo, producer)

		_, err := service.CreateShipment(context.Background(), correlationID, orderID, address)

		if !errors.Is(err, wantErr) {
			t.Errorf("err = %v, want %v", err, wantErr)
		}
	})

	t.Run("joins both errors when the creation-failed publish also fails", func(t *testing.T) {
		orderID := uint64(100)
		correlationID := "corr-1"
		address := testAddress(t)
		createErr := errors.New("db down")
		pubErr := errors.New("kafka down")
		shipmentRepo := repomocks.NewMockShipmentRepository(gomock.NewController(t))
		producer := kafkamocks.NewMockShipmentKafkaProducer(gomock.NewController(t))

		shipmentRepo.EXPECT().Create(gomock.Any(), orderID, address).Return(nil, createErr).Times(1)
		producer.EXPECT().PublishShipmentCreationFailed(gomock.Any(), correlationID, orderID).Return(pubErr).Times(1)

		service := NewShipmentServiceImpl(shipmentRepo, producer)

		_, err := service.CreateShipment(context.Background(), correlationID, orderID, address)

		if !errors.Is(err, createErr) {
			t.Errorf("err should wrap the create error: %v", err)
		}
		if !errors.Is(err, pubErr) {
			t.Errorf("err should wrap the publish error: %v", err)
		}
	})

	t.Run("propagates an error publishing the status change after a successful create", func(t *testing.T) {
		orderID := uint64(100)
		correlationID := "corr-1"
		address := testAddress(t)
		wantErr := errors.New("kafka down")
		persisted := testShipment(t, 1, model.Created, model.HolderInventory)
		shipmentRepo := repomocks.NewMockShipmentRepository(gomock.NewController(t))
		producer := kafkamocks.NewMockShipmentKafkaProducer(gomock.NewController(t))

		shipmentRepo.EXPECT().Create(gomock.Any(), orderID, address).Return(persisted, nil).Times(1)
		producer.EXPECT().PublishShipmentStatusChanged(gomock.Any(), correlationID, persisted).Return(wantErr).Times(1)

		service := NewShipmentServiceImpl(shipmentRepo, producer)

		_, err := service.CreateShipment(context.Background(), correlationID, orderID, address)

		if !errors.Is(err, wantErr) {
			t.Errorf("err = %v, want %v", err, wantErr)
		}
	})
}

func TestShipmentServiceImpl_StartDelivery(t *testing.T) {
	t.Run("starts delivery, persists, and publishes the status change", func(t *testing.T) {
		id := uint64(1)
		shipment := testShipment(t, id, model.Created, model.HolderInventory)
		shipmentRepo := repomocks.NewMockShipmentRepository(gomock.NewController(t))
		producer := kafkamocks.NewMockShipmentKafkaProducer(gomock.NewController(t))

		shipmentRepo.EXPECT().GetByID(gomock.Any(), id).Return(shipment, nil).Times(1)

		var transitioned *model.Shipment
		shipmentRepo.EXPECT().Update(gomock.Any(), gomock.Any()).Times(1).DoAndReturn(
			func(ctx context.Context, s *model.Shipment) (*model.Shipment, error) {
				transitioned = s
				return s, nil
			},
		)
		producer.EXPECT().PublishShipmentStatusChanged(gomock.Any(), "100", gomock.Any()).Return(nil).Times(1)

		service := NewShipmentServiceImpl(shipmentRepo, producer)

		got, err := service.StartDelivery(context.Background(), id)

		if err != nil {
			t.Fatalf("unexpected error: %v", err)
		}
		if transitioned == nil || transitioned.Status() != model.InDelivery || transitioned.Holder() != model.HolderCourier {
			t.Fatalf("unexpected transition: %+v", transitioned)
		}
		if !reflect.DeepEqual(got, transitioned) {
			t.Errorf("StartDelivery() = %+v, want %+v", got, transitioned)
		}
	})

	t.Run("propagates an error fetching the shipment", func(t *testing.T) {
		id := uint64(1)
		wantErr := errors.New("db down")
		shipmentRepo := repomocks.NewMockShipmentRepository(gomock.NewController(t))
		producer := kafkamocks.NewMockShipmentKafkaProducer(gomock.NewController(t))

		shipmentRepo.EXPECT().GetByID(gomock.Any(), id).Return(nil, wantErr).Times(1)
		shipmentRepo.EXPECT().Update(gomock.Any(), gomock.Any()).Times(0)
		producer.EXPECT().PublishShipmentStatusChanged(gomock.Any(), gomock.Any(), gomock.Any()).Times(0)

		service := NewShipmentServiceImpl(shipmentRepo, producer)

		_, err := service.StartDelivery(context.Background(), id)

		if !errors.Is(err, wantErr) {
			t.Errorf("err = %v, want %v", err, wantErr)
		}
	})

	t.Run("propagates a domain error when the shipment is not in a startable status", func(t *testing.T) {
		id := uint64(1)
		shipment := testShipment(t, id, model.Delivered, model.HolderCourier) // already terminal
		shipmentRepo := repomocks.NewMockShipmentRepository(gomock.NewController(t))
		producer := kafkamocks.NewMockShipmentKafkaProducer(gomock.NewController(t))

		shipmentRepo.EXPECT().GetByID(gomock.Any(), id).Return(shipment, nil).Times(1)
		shipmentRepo.EXPECT().Update(gomock.Any(), gomock.Any()).Times(0)
		producer.EXPECT().PublishShipmentStatusChanged(gomock.Any(), gomock.Any(), gomock.Any()).Times(0)

		service := NewShipmentServiceImpl(shipmentRepo, producer)

		_, err := service.StartDelivery(context.Background(), id)

		if !errors.Is(err, model.ErrInvalidStatusTransition) {
			t.Errorf("err = %v, want %v", err, model.ErrInvalidStatusTransition)
		}
	})

	t.Run("propagates an error persisting the transitioned shipment", func(t *testing.T) {
		id := uint64(1)
		wantErr := errors.New("db down")
		shipment := testShipment(t, id, model.Created, model.HolderInventory)
		shipmentRepo := repomocks.NewMockShipmentRepository(gomock.NewController(t))
		producer := kafkamocks.NewMockShipmentKafkaProducer(gomock.NewController(t))

		shipmentRepo.EXPECT().GetByID(gomock.Any(), id).Return(shipment, nil).Times(1)
		shipmentRepo.EXPECT().Update(gomock.Any(), gomock.Any()).Return(nil, wantErr).Times(1)
		producer.EXPECT().PublishShipmentStatusChanged(gomock.Any(), gomock.Any(), gomock.Any()).Times(0)

		service := NewShipmentServiceImpl(shipmentRepo, producer)

		_, err := service.StartDelivery(context.Background(), id)

		if !errors.Is(err, wantErr) {
			t.Errorf("err = %v, want %v", err, wantErr)
		}
	})

	t.Run("propagates an error publishing the status change", func(t *testing.T) {
		id := uint64(1)
		wantErr := errors.New("kafka down")
		shipment := testShipment(t, id, model.Created, model.HolderInventory)
		updated := testShipment(t, id, model.InDelivery, model.HolderCourier)
		shipmentRepo := repomocks.NewMockShipmentRepository(gomock.NewController(t))
		producer := kafkamocks.NewMockShipmentKafkaProducer(gomock.NewController(t))

		shipmentRepo.EXPECT().GetByID(gomock.Any(), id).Return(shipment, nil).Times(1)
		shipmentRepo.EXPECT().Update(gomock.Any(), gomock.Any()).Return(updated, nil).Times(1)
		producer.EXPECT().PublishShipmentStatusChanged(gomock.Any(), "100", updated).Return(wantErr).Times(1)

		service := NewShipmentServiceImpl(shipmentRepo, producer)

		_, err := service.StartDelivery(context.Background(), id)

		if !errors.Is(err, wantErr) {
			t.Errorf("err = %v, want %v", err, wantErr)
		}
	})
}

func TestShipmentServiceImpl_Deliver(t *testing.T) {
	id := uint64(1)
	shipment := testShipment(t, id, model.InDelivery, model.HolderCourier)
	shipmentRepo := repomocks.NewMockShipmentRepository(gomock.NewController(t))
	producer := kafkamocks.NewMockShipmentKafkaProducer(gomock.NewController(t))

	shipmentRepo.EXPECT().GetByID(gomock.Any(), id).Return(shipment, nil).Times(1)

	var transitioned *model.Shipment
	shipmentRepo.EXPECT().Update(gomock.Any(), gomock.Any()).Times(1).DoAndReturn(
		func(ctx context.Context, s *model.Shipment) (*model.Shipment, error) {
			transitioned = s
			return s, nil
		},
	)
	producer.EXPECT().PublishShipmentStatusChanged(gomock.Any(), "100", gomock.Any()).Return(nil).Times(1)

	service := NewShipmentServiceImpl(shipmentRepo, producer)

	got, err := service.Deliver(context.Background(), id)

	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if transitioned == nil || transitioned.Status() != model.Delivered || transitioned.Holder() != model.HolderCourier {
		t.Fatalf("unexpected transition: %+v", transitioned)
	}
	if !reflect.DeepEqual(got, transitioned) {
		t.Errorf("Deliver() = %+v, want %+v", got, transitioned)
	}
}

func TestShipmentServiceImpl_Lose(t *testing.T) {
	id := uint64(1)
	shipment := testShipment(t, id, model.InDelivery, model.HolderCourier)
	shipmentRepo := repomocks.NewMockShipmentRepository(gomock.NewController(t))
	producer := kafkamocks.NewMockShipmentKafkaProducer(gomock.NewController(t))

	shipmentRepo.EXPECT().GetByID(gomock.Any(), id).Return(shipment, nil).Times(1)

	var transitioned *model.Shipment
	shipmentRepo.EXPECT().Update(gomock.Any(), gomock.Any()).Times(1).DoAndReturn(
		func(ctx context.Context, s *model.Shipment) (*model.Shipment, error) {
			transitioned = s
			return s, nil
		},
	)
	producer.EXPECT().PublishShipmentStatusChanged(gomock.Any(), "100", gomock.Any()).Return(nil).Times(1)

	service := NewShipmentServiceImpl(shipmentRepo, producer)

	got, err := service.Lose(context.Background(), id)

	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if transitioned == nil || transitioned.Status() != model.Lost || transitioned.Holder() != model.HolderCourier {
		t.Fatalf("unexpected transition: %+v", transitioned)
	}
	if !reflect.DeepEqual(got, transitioned) {
		t.Errorf("Lose() = %+v, want %+v", got, transitioned)
	}
}

func TestShipmentServiceImpl_Cancel(t *testing.T) {
	id := uint64(1)
	shipment := testShipment(t, id, model.Created, model.HolderInventory)
	shipmentRepo := repomocks.NewMockShipmentRepository(gomock.NewController(t))
	producer := kafkamocks.NewMockShipmentKafkaProducer(gomock.NewController(t))

	shipmentRepo.EXPECT().GetByID(gomock.Any(), id).Return(shipment, nil).Times(1)

	var transitioned *model.Shipment
	shipmentRepo.EXPECT().Update(gomock.Any(), gomock.Any()).Times(1).DoAndReturn(
		func(ctx context.Context, s *model.Shipment) (*model.Shipment, error) {
			transitioned = s
			return s, nil
		},
	)
	producer.EXPECT().PublishShipmentStatusChanged(gomock.Any(), "100", gomock.Any()).Return(nil).Times(1)

	service := NewShipmentServiceImpl(shipmentRepo, producer)

	got, err := service.Cancel(context.Background(), id)

	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if transitioned == nil || transitioned.Status() != model.Cancelled || transitioned.Holder() != model.HolderInventory {
		t.Fatalf("unexpected transition: %+v", transitioned)
	}
	if !reflect.DeepEqual(got, transitioned) {
		t.Errorf("Cancel() = %+v, want %+v", got, transitioned)
	}
}

func TestShipmentServiceImpl_ConfirmReturnedToInventory(t *testing.T) {
	t.Run("moves the holder back to inventory and publishes the returned event", func(t *testing.T) {
		id := uint64(1)
		shipment := testShipment(t, id, model.Cancelled, model.HolderCourier)
		shipmentRepo := repomocks.NewMockShipmentRepository(gomock.NewController(t))
		producer := kafkamocks.NewMockShipmentKafkaProducer(gomock.NewController(t))

		shipmentRepo.EXPECT().GetByID(gomock.Any(), id).Return(shipment, nil).Times(1)

		var transitioned *model.Shipment
		shipmentRepo.EXPECT().Update(gomock.Any(), gomock.Any()).Times(1).DoAndReturn(
			func(ctx context.Context, s *model.Shipment) (*model.Shipment, error) {
				transitioned = s
				return s, nil
			},
		)
		producer.EXPECT().PublishShipmentReturnedToInventory(gomock.Any(), "100", gomock.Any()).Return(nil).Times(1)
		producer.EXPECT().PublishShipmentStatusChanged(gomock.Any(), gomock.Any(), gomock.Any()).Times(0)

		service := NewShipmentServiceImpl(shipmentRepo, producer)

		got, err := service.ConfirmReturnedToInventory(context.Background(), id)

		if err != nil {
			t.Fatalf("unexpected error: %v", err)
		}
		if transitioned == nil || transitioned.Status() != model.Cancelled || transitioned.Holder() != model.HolderInventory {
			t.Fatalf("unexpected transition: %+v", transitioned)
		}
		if !reflect.DeepEqual(got, transitioned) {
			t.Errorf("ConfirmReturnedToInventory() = %+v, want %+v", got, transitioned)
		}
	})

	t.Run("propagates an error fetching the shipment", func(t *testing.T) {
		id := uint64(1)
		wantErr := errors.New("db down")
		shipmentRepo := repomocks.NewMockShipmentRepository(gomock.NewController(t))
		producer := kafkamocks.NewMockShipmentKafkaProducer(gomock.NewController(t))

		shipmentRepo.EXPECT().GetByID(gomock.Any(), id).Return(nil, wantErr).Times(1)
		shipmentRepo.EXPECT().Update(gomock.Any(), gomock.Any()).Times(0)
		producer.EXPECT().PublishShipmentReturnedToInventory(gomock.Any(), gomock.Any(), gomock.Any()).Times(0)

		service := NewShipmentServiceImpl(shipmentRepo, producer)

		_, err := service.ConfirmReturnedToInventory(context.Background(), id)

		if !errors.Is(err, wantErr) {
			t.Errorf("err = %v, want %v", err, wantErr)
		}
	})

	t.Run("propagates a domain error when the goods were never picked up by a courier", func(t *testing.T) {
		id := uint64(1)
		shipment := testShipment(t, id, model.Cancelled, model.HolderInventory) // never left the warehouse
		shipmentRepo := repomocks.NewMockShipmentRepository(gomock.NewController(t))
		producer := kafkamocks.NewMockShipmentKafkaProducer(gomock.NewController(t))

		shipmentRepo.EXPECT().GetByID(gomock.Any(), id).Return(shipment, nil).Times(1)
		shipmentRepo.EXPECT().Update(gomock.Any(), gomock.Any()).Times(0)
		producer.EXPECT().PublishShipmentReturnedToInventory(gomock.Any(), gomock.Any(), gomock.Any()).Times(0)

		service := NewShipmentServiceImpl(shipmentRepo, producer)

		_, err := service.ConfirmReturnedToInventory(context.Background(), id)

		if !errors.Is(err, model.ErrInvalidStatusTransition) {
			t.Errorf("err = %v, want %v", err, model.ErrInvalidStatusTransition)
		}
	})

	t.Run("propagates an error persisting the transitioned shipment", func(t *testing.T) {
		id := uint64(1)
		wantErr := errors.New("db down")
		shipment := testShipment(t, id, model.Cancelled, model.HolderCourier)
		shipmentRepo := repomocks.NewMockShipmentRepository(gomock.NewController(t))
		producer := kafkamocks.NewMockShipmentKafkaProducer(gomock.NewController(t))

		shipmentRepo.EXPECT().GetByID(gomock.Any(), id).Return(shipment, nil).Times(1)
		shipmentRepo.EXPECT().Update(gomock.Any(), gomock.Any()).Return(nil, wantErr).Times(1)
		producer.EXPECT().PublishShipmentReturnedToInventory(gomock.Any(), gomock.Any(), gomock.Any()).Times(0)

		service := NewShipmentServiceImpl(shipmentRepo, producer)

		_, err := service.ConfirmReturnedToInventory(context.Background(), id)

		if !errors.Is(err, wantErr) {
			t.Errorf("err = %v, want %v", err, wantErr)
		}
	})

	t.Run("propagates an error publishing the returned-to-inventory event", func(t *testing.T) {
		id := uint64(1)
		wantErr := errors.New("kafka down")
		shipment := testShipment(t, id, model.Cancelled, model.HolderCourier)
		updated := testShipment(t, id, model.Cancelled, model.HolderInventory)
		shipmentRepo := repomocks.NewMockShipmentRepository(gomock.NewController(t))
		producer := kafkamocks.NewMockShipmentKafkaProducer(gomock.NewController(t))

		shipmentRepo.EXPECT().GetByID(gomock.Any(), id).Return(shipment, nil).Times(1)
		shipmentRepo.EXPECT().Update(gomock.Any(), gomock.Any()).Return(updated, nil).Times(1)
		producer.EXPECT().PublishShipmentReturnedToInventory(gomock.Any(), "100", updated).Return(wantErr).Times(1)

		service := NewShipmentServiceImpl(shipmentRepo, producer)

		_, err := service.ConfirmReturnedToInventory(context.Background(), id)

		if !errors.Is(err, wantErr) {
			t.Errorf("err = %v, want %v", err, wantErr)
		}
	})
}
