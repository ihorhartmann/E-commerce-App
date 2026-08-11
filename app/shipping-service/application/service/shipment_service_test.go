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
		ctrl := gomock.NewController(t)
		shipment := testShipment(t, 1, model.Created, model.HolderInventory)
		shipmentRepo := repomocks.NewMockShipmentRepository(ctrl)
		shipmentRepo.EXPECT().GetByTrackingNumber(gomock.Any(), "TRACK-1").Return(shipment, nil).Times(1)

		service := NewShipmentServiceImpl(shipmentRepo, kafkamocks.NewMockShipmentKafkaProducer(ctrl))

		got, err := service.GetByTrackingNumber(context.Background(), "TRACK-1")

		if err != nil {
			t.Fatalf("unexpected error: %v", err)
		}
		if !reflect.DeepEqual(got, shipment) {
			t.Errorf("GetByTrackingNumber() = %+v, want %+v", got, shipment)
		}
	})

	t.Run("rejects a blank tracking number without calling the repository", func(t *testing.T) {
		ctrl := gomock.NewController(t)
		shipmentRepo := repomocks.NewMockShipmentRepository(ctrl)
		shipmentRepo.EXPECT().GetByTrackingNumber(gomock.Any(), gomock.Any()).Times(0)

		service := NewShipmentServiceImpl(shipmentRepo, kafkamocks.NewMockShipmentKafkaProducer(ctrl))

		_, err := service.GetByTrackingNumber(context.Background(), "   ")

		if !errors.Is(err, ErrInvalidTrackingNumber) {
			t.Errorf("err = %v, want %v", err, ErrInvalidTrackingNumber)
		}
	})
}

func TestShipmentServiceImpl_CreateShipment(t *testing.T) {
	t.Run("persists a new shipment via the repository, then publishes the status change", func(t *testing.T) {
		ctrl := gomock.NewController(t)
		address := testAddress(t)
		persisted := testShipment(t, 1, model.Created, model.HolderInventory)
		shipmentRepo := repomocks.NewMockShipmentRepository(ctrl)
		producer := kafkamocks.NewMockShipmentKafkaProducer(ctrl)

		shipmentRepo.EXPECT().Create(gomock.Any(), uint64(100), address).Return(persisted, nil).Times(1)
		producer.EXPECT().PublishShipmentStatusChanged(gomock.Any(), "corr-1", persisted).Return(nil).Times(1)

		service := NewShipmentServiceImpl(shipmentRepo, producer)

		got, err := service.CreateShipment(context.Background(), "corr-1", 100, address)

		if err != nil {
			t.Fatalf("unexpected error: %v", err)
		}
		if !reflect.DeepEqual(got, persisted) {
			t.Errorf("CreateShipment() = %+v, want %+v", got, persisted)
		}
	})

	t.Run("publishes creation-failed and returns the original error when persisting fails", func(t *testing.T) {
		ctrl := gomock.NewController(t)
		address := testAddress(t)
		wantErr := errors.New("db down")
		shipmentRepo := repomocks.NewMockShipmentRepository(ctrl)
		producer := kafkamocks.NewMockShipmentKafkaProducer(ctrl)

		shipmentRepo.EXPECT().Create(gomock.Any(), uint64(100), address).Return(nil, wantErr).Times(1)
		producer.EXPECT().PublishShipmentCreationFailed(gomock.Any(), "corr-1", uint64(100)).Return(nil).Times(1)
		producer.EXPECT().PublishShipmentStatusChanged(gomock.Any(), gomock.Any(), gomock.Any()).Times(0)

		service := NewShipmentServiceImpl(shipmentRepo, producer)

		_, err := service.CreateShipment(context.Background(), "corr-1", 100, address)

		if !errors.Is(err, wantErr) {
			t.Errorf("err = %v, want %v", err, wantErr)
		}
	})

	t.Run("joins both errors when the creation-failed publish also fails", func(t *testing.T) {
		ctrl := gomock.NewController(t)
		address := testAddress(t)
		createErr := errors.New("db down")
		pubErr := errors.New("kafka down")
		shipmentRepo := repomocks.NewMockShipmentRepository(ctrl)
		producer := kafkamocks.NewMockShipmentKafkaProducer(ctrl)

		shipmentRepo.EXPECT().Create(gomock.Any(), uint64(100), address).Return(nil, createErr).Times(1)
		producer.EXPECT().PublishShipmentCreationFailed(gomock.Any(), "corr-1", uint64(100)).Return(pubErr).Times(1)

		service := NewShipmentServiceImpl(shipmentRepo, producer)

		_, err := service.CreateShipment(context.Background(), "corr-1", 100, address)

		if !errors.Is(err, createErr) {
			t.Errorf("err should wrap the create error: %v", err)
		}
		if !errors.Is(err, pubErr) {
			t.Errorf("err should wrap the publish error: %v", err)
		}
	})

	t.Run("propagates an error publishing the status change after a successful create", func(t *testing.T) {
		ctrl := gomock.NewController(t)
		address := testAddress(t)
		wantErr := errors.New("kafka down")
		persisted := testShipment(t, 1, model.Created, model.HolderInventory)
		shipmentRepo := repomocks.NewMockShipmentRepository(ctrl)
		producer := kafkamocks.NewMockShipmentKafkaProducer(ctrl)

		shipmentRepo.EXPECT().Create(gomock.Any(), uint64(100), address).Return(persisted, nil).Times(1)
		producer.EXPECT().PublishShipmentStatusChanged(gomock.Any(), "corr-1", persisted).Return(wantErr).Times(1)

		service := NewShipmentServiceImpl(shipmentRepo, producer)

		_, err := service.CreateShipment(context.Background(), "corr-1", 100, address)

		if !errors.Is(err, wantErr) {
			t.Errorf("err = %v, want %v", err, wantErr)
		}
	})
}

func TestShipmentServiceImpl_StartDelivery(t *testing.T) {
	t.Run("starts delivery, persists, and publishes the status change", func(t *testing.T) {
		ctrl := gomock.NewController(t)
		shipment := testShipment(t, 1, model.Created, model.HolderInventory)
		shipmentRepo := repomocks.NewMockShipmentRepository(ctrl)
		producer := kafkamocks.NewMockShipmentKafkaProducer(ctrl)

		shipmentRepo.EXPECT().GetByID(gomock.Any(), uint64(1)).Return(shipment, nil).Times(1)

		var transitioned *model.Shipment
		shipmentRepo.EXPECT().Update(gomock.Any(), gomock.Any()).Times(1).DoAndReturn(
			func(ctx context.Context, s *model.Shipment) (*model.Shipment, error) {
				transitioned = s
				return s, nil
			},
		)
		producer.EXPECT().PublishShipmentStatusChanged(gomock.Any(), "100", gomock.Any()).Return(nil).Times(1)

		service := NewShipmentServiceImpl(shipmentRepo, producer)

		got, err := service.StartDelivery(context.Background(), 1)

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
		ctrl := gomock.NewController(t)
		wantErr := errors.New("db down")
		shipmentRepo := repomocks.NewMockShipmentRepository(ctrl)
		producer := kafkamocks.NewMockShipmentKafkaProducer(ctrl)

		shipmentRepo.EXPECT().GetByID(gomock.Any(), uint64(1)).Return(nil, wantErr).Times(1)
		shipmentRepo.EXPECT().Update(gomock.Any(), gomock.Any()).Times(0)
		producer.EXPECT().PublishShipmentStatusChanged(gomock.Any(), gomock.Any(), gomock.Any()).Times(0)

		service := NewShipmentServiceImpl(shipmentRepo, producer)

		_, err := service.StartDelivery(context.Background(), 1)

		if !errors.Is(err, wantErr) {
			t.Errorf("err = %v, want %v", err, wantErr)
		}
	})

	t.Run("propagates a domain error when the shipment is not in a startable status", func(t *testing.T) {
		ctrl := gomock.NewController(t)
		shipment := testShipment(t, 1, model.Delivered, model.HolderCourier) // already terminal
		shipmentRepo := repomocks.NewMockShipmentRepository(ctrl)
		producer := kafkamocks.NewMockShipmentKafkaProducer(ctrl)

		shipmentRepo.EXPECT().GetByID(gomock.Any(), uint64(1)).Return(shipment, nil).Times(1)
		shipmentRepo.EXPECT().Update(gomock.Any(), gomock.Any()).Times(0)
		producer.EXPECT().PublishShipmentStatusChanged(gomock.Any(), gomock.Any(), gomock.Any()).Times(0)

		service := NewShipmentServiceImpl(shipmentRepo, producer)

		_, err := service.StartDelivery(context.Background(), 1)

		if !errors.Is(err, model.ErrInvalidStatusTransition) {
			t.Errorf("err = %v, want %v", err, model.ErrInvalidStatusTransition)
		}
	})

	t.Run("propagates an error persisting the transitioned shipment", func(t *testing.T) {
		ctrl := gomock.NewController(t)
		wantErr := errors.New("db down")
		shipment := testShipment(t, 1, model.Created, model.HolderInventory)
		shipmentRepo := repomocks.NewMockShipmentRepository(ctrl)
		producer := kafkamocks.NewMockShipmentKafkaProducer(ctrl)

		shipmentRepo.EXPECT().GetByID(gomock.Any(), uint64(1)).Return(shipment, nil).Times(1)
		shipmentRepo.EXPECT().Update(gomock.Any(), gomock.Any()).Return(nil, wantErr).Times(1)
		producer.EXPECT().PublishShipmentStatusChanged(gomock.Any(), gomock.Any(), gomock.Any()).Times(0)

		service := NewShipmentServiceImpl(shipmentRepo, producer)

		_, err := service.StartDelivery(context.Background(), 1)

		if !errors.Is(err, wantErr) {
			t.Errorf("err = %v, want %v", err, wantErr)
		}
	})

	t.Run("propagates an error publishing the status change", func(t *testing.T) {
		ctrl := gomock.NewController(t)
		wantErr := errors.New("kafka down")
		shipment := testShipment(t, 1, model.Created, model.HolderInventory)
		updated := testShipment(t, 1, model.InDelivery, model.HolderCourier)
		shipmentRepo := repomocks.NewMockShipmentRepository(ctrl)
		producer := kafkamocks.NewMockShipmentKafkaProducer(ctrl)

		shipmentRepo.EXPECT().GetByID(gomock.Any(), uint64(1)).Return(shipment, nil).Times(1)
		shipmentRepo.EXPECT().Update(gomock.Any(), gomock.Any()).Return(updated, nil).Times(1)
		producer.EXPECT().PublishShipmentStatusChanged(gomock.Any(), "100", updated).Return(wantErr).Times(1)

		service := NewShipmentServiceImpl(shipmentRepo, producer)

		_, err := service.StartDelivery(context.Background(), 1)

		if !errors.Is(err, wantErr) {
			t.Errorf("err = %v, want %v", err, wantErr)
		}
	})
}

func TestShipmentServiceImpl_Deliver(t *testing.T) {
	ctrl := gomock.NewController(t)
	shipment := testShipment(t, 1, model.InDelivery, model.HolderCourier)
	shipmentRepo := repomocks.NewMockShipmentRepository(ctrl)
	producer := kafkamocks.NewMockShipmentKafkaProducer(ctrl)

	shipmentRepo.EXPECT().GetByID(gomock.Any(), uint64(1)).Return(shipment, nil).Times(1)

	var transitioned *model.Shipment
	shipmentRepo.EXPECT().Update(gomock.Any(), gomock.Any()).Times(1).DoAndReturn(
		func(ctx context.Context, s *model.Shipment) (*model.Shipment, error) {
			transitioned = s
			return s, nil
		},
	)
	producer.EXPECT().PublishShipmentStatusChanged(gomock.Any(), "100", gomock.Any()).Return(nil).Times(1)

	service := NewShipmentServiceImpl(shipmentRepo, producer)

	got, err := service.Deliver(context.Background(), 1)

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
	ctrl := gomock.NewController(t)
	shipment := testShipment(t, 1, model.InDelivery, model.HolderCourier)
	shipmentRepo := repomocks.NewMockShipmentRepository(ctrl)
	producer := kafkamocks.NewMockShipmentKafkaProducer(ctrl)

	shipmentRepo.EXPECT().GetByID(gomock.Any(), uint64(1)).Return(shipment, nil).Times(1)

	var transitioned *model.Shipment
	shipmentRepo.EXPECT().Update(gomock.Any(), gomock.Any()).Times(1).DoAndReturn(
		func(ctx context.Context, s *model.Shipment) (*model.Shipment, error) {
			transitioned = s
			return s, nil
		},
	)
	producer.EXPECT().PublishShipmentStatusChanged(gomock.Any(), "100", gomock.Any()).Return(nil).Times(1)

	service := NewShipmentServiceImpl(shipmentRepo, producer)

	got, err := service.Lose(context.Background(), 1)

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
	ctrl := gomock.NewController(t)
	shipment := testShipment(t, 1, model.Created, model.HolderInventory)
	shipmentRepo := repomocks.NewMockShipmentRepository(ctrl)
	producer := kafkamocks.NewMockShipmentKafkaProducer(ctrl)

	shipmentRepo.EXPECT().GetByID(gomock.Any(), uint64(1)).Return(shipment, nil).Times(1)

	var transitioned *model.Shipment
	shipmentRepo.EXPECT().Update(gomock.Any(), gomock.Any()).Times(1).DoAndReturn(
		func(ctx context.Context, s *model.Shipment) (*model.Shipment, error) {
			transitioned = s
			return s, nil
		},
	)
	producer.EXPECT().PublishShipmentStatusChanged(gomock.Any(), "100", gomock.Any()).Return(nil).Times(1)

	service := NewShipmentServiceImpl(shipmentRepo, producer)

	got, err := service.Cancel(context.Background(), 1)

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
		ctrl := gomock.NewController(t)
		shipment := testShipment(t, 1, model.Cancelled, model.HolderCourier)
		shipmentRepo := repomocks.NewMockShipmentRepository(ctrl)
		producer := kafkamocks.NewMockShipmentKafkaProducer(ctrl)

		shipmentRepo.EXPECT().GetByID(gomock.Any(), uint64(1)).Return(shipment, nil).Times(1)

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

		got, err := service.ConfirmReturnedToInventory(context.Background(), 1)

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
		ctrl := gomock.NewController(t)
		wantErr := errors.New("db down")
		shipmentRepo := repomocks.NewMockShipmentRepository(ctrl)
		producer := kafkamocks.NewMockShipmentKafkaProducer(ctrl)

		shipmentRepo.EXPECT().GetByID(gomock.Any(), uint64(1)).Return(nil, wantErr).Times(1)
		shipmentRepo.EXPECT().Update(gomock.Any(), gomock.Any()).Times(0)
		producer.EXPECT().PublishShipmentReturnedToInventory(gomock.Any(), gomock.Any(), gomock.Any()).Times(0)

		service := NewShipmentServiceImpl(shipmentRepo, producer)

		_, err := service.ConfirmReturnedToInventory(context.Background(), 1)

		if !errors.Is(err, wantErr) {
			t.Errorf("err = %v, want %v", err, wantErr)
		}
	})

	t.Run("propagates a domain error when the goods were never picked up by a courier", func(t *testing.T) {
		ctrl := gomock.NewController(t)
		shipment := testShipment(t, 1, model.Cancelled, model.HolderInventory) // never left the warehouse
		shipmentRepo := repomocks.NewMockShipmentRepository(ctrl)
		producer := kafkamocks.NewMockShipmentKafkaProducer(ctrl)

		shipmentRepo.EXPECT().GetByID(gomock.Any(), uint64(1)).Return(shipment, nil).Times(1)
		shipmentRepo.EXPECT().Update(gomock.Any(), gomock.Any()).Times(0)
		producer.EXPECT().PublishShipmentReturnedToInventory(gomock.Any(), gomock.Any(), gomock.Any()).Times(0)

		service := NewShipmentServiceImpl(shipmentRepo, producer)

		_, err := service.ConfirmReturnedToInventory(context.Background(), 1)

		if !errors.Is(err, model.ErrInvalidStatusTransition) {
			t.Errorf("err = %v, want %v", err, model.ErrInvalidStatusTransition)
		}
	})

	t.Run("propagates an error persisting the transitioned shipment", func(t *testing.T) {
		ctrl := gomock.NewController(t)
		wantErr := errors.New("db down")
		shipment := testShipment(t, 1, model.Cancelled, model.HolderCourier)
		shipmentRepo := repomocks.NewMockShipmentRepository(ctrl)
		producer := kafkamocks.NewMockShipmentKafkaProducer(ctrl)

		shipmentRepo.EXPECT().GetByID(gomock.Any(), uint64(1)).Return(shipment, nil).Times(1)
		shipmentRepo.EXPECT().Update(gomock.Any(), gomock.Any()).Return(nil, wantErr).Times(1)
		producer.EXPECT().PublishShipmentReturnedToInventory(gomock.Any(), gomock.Any(), gomock.Any()).Times(0)

		service := NewShipmentServiceImpl(shipmentRepo, producer)

		_, err := service.ConfirmReturnedToInventory(context.Background(), 1)

		if !errors.Is(err, wantErr) {
			t.Errorf("err = %v, want %v", err, wantErr)
		}
	})

	t.Run("propagates an error publishing the returned-to-inventory event", func(t *testing.T) {
		ctrl := gomock.NewController(t)
		wantErr := errors.New("kafka down")
		shipment := testShipment(t, 1, model.Cancelled, model.HolderCourier)
		updated := testShipment(t, 1, model.Cancelled, model.HolderInventory)
		shipmentRepo := repomocks.NewMockShipmentRepository(ctrl)
		producer := kafkamocks.NewMockShipmentKafkaProducer(ctrl)

		shipmentRepo.EXPECT().GetByID(gomock.Any(), uint64(1)).Return(shipment, nil).Times(1)
		shipmentRepo.EXPECT().Update(gomock.Any(), gomock.Any()).Return(updated, nil).Times(1)
		producer.EXPECT().PublishShipmentReturnedToInventory(gomock.Any(), "100", updated).Return(wantErr).Times(1)

		service := NewShipmentServiceImpl(shipmentRepo, producer)

		_, err := service.ConfirmReturnedToInventory(context.Background(), 1)

		if !errors.Is(err, wantErr) {
			t.Errorf("err = %v, want %v", err, wantErr)
		}
	})
}
