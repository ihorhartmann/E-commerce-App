package service

import (
	"context"
	"errors"
	"log/slog"
	"math"
	"reflect"
	"slices"
	"testing"
	"time"

	"go.uber.org/mock/gomock"

	"shippingservice/application/port/common/logging"
	portin "shippingservice/application/port/in"
	"shippingservice/application/port/in/dto"
	idempotencydto "shippingservice/application/port/out/idempotency/dto"
	idempotencymocks "shippingservice/application/port/out/idempotency/mocks"
	kafkamocks "shippingservice/application/port/out/kafka/mocks"
	"shippingservice/application/port/out/repository"
	repomocks "shippingservice/application/port/out/repository/mocks"
	"shippingservice/domain/apperror"
	"shippingservice/domain/model"
	"shippingservice/domain/value"

	"github.com/google/uuid"
)

// noopTxExecutor runs the action directly, without an actual transaction - the application-layer
// unit tests only need Transaction() to invoke its argument, not to verify tx boundaries.
type noopTxExecutor struct{}

func (noopTxExecutor) Transaction(ctx context.Context, action func(context.Context) error) error {
	return action(ctx)
}

func testAddress(t *testing.T) value.Address {
	t.Helper()
	address, err := value.NewAddress("Kyiv", "01001", "Khreshchatyk 1", "")
	if err != nil {
		t.Fatalf("unexpected error building test address: %v", err)
	}
	return address
}

// testShipment builds a shipment of order 100; courierID must be uuid.Nil before pickup and set after it. A cancelled
// one is cancelled with its order - the only reason that fits both before and after pickup.
func testShipment(t *testing.T, id uint64, status model.ShipmentStatus, holder model.ShipmentHolder, courierID uuid.UUID) *model.Shipment {
	t.Helper()
	now := time.Now()
	cancellationReason := model.NoCancellationReason
	if status == model.Cancelled {
		cancellationReason = model.OrderCancelled
	}
	shipment, err := model.NewExistingShipment(id, 100, courierID, testAddress(t), status, holder, cancellationReason, now, now, 0)
	if err != nil {
		t.Fatalf("unexpected error building test shipment: %v", err)
	}
	return shipment
}

func newTestService(t *testing.T, shipmentRepo *repomocks.MockShipmentRepository) portin.ShipmentService {
	t.Helper()
	return NewShipmentServiceImpl(
		shipmentRepo,
		kafkamocks.NewMockShipmentKafkaProducer(gomock.NewController(t)),
		idempotencymocks.NewMockProcessedKafkaMessageStore(gomock.NewController(t)),
		noopTxExecutor{},
	)
}

func TestShipmentServiceImpl_GetByID(t *testing.T) {
	t.Run("returns the shipment from the repository", func(t *testing.T) {
		id := uint64(1)
		shipment := testShipment(t, id, model.Created, model.HolderInventory, uuid.Nil)
		shipmentRepo := repomocks.NewMockShipmentRepository(gomock.NewController(t))
		shipmentRepo.EXPECT().GetByID(gomock.Any(), id).Return(shipment, nil).Times(1)

		got, err := newTestService(t, shipmentRepo).GetByID(context.Background(), id)

		if err != nil {
			t.Fatalf("unexpected error: %v", err)
		}
		if got != shipment {
			t.Errorf("GetByID() = %+v, want %+v", got, shipment)
		}
	})
}

// invalidPages are pages GetAvailableShipments and GetCourierActiveShipments reject without reading any shipment.
var invalidPages = []struct {
	name string
	page int
	size int
}{
	{name: "a negative page", page: -1, size: 10},
	{name: "a zero size", page: 0, size: 0},
	{name: "a size above the maximum", page: 0, size: maxPageSize + 1},
	{name: "a page too large to compute its offset", page: math.MaxInt32 + 1, size: 5},
}

func TestShipmentServiceImpl_GetAvailableShipments(t *testing.T) {
	t.Run("returns the requested page of available shipments and the number of all of them", func(t *testing.T) {
		page := 3
		size := 10
		totalElements := int64(35)
		shipments := []*model.Shipment{testShipment(t, 31, model.Created, model.HolderInventory, uuid.Nil)}
		shipmentRepo := repomocks.NewMockShipmentRepository(gomock.NewController(t))
		shipmentRepo.EXPECT().GetAvailableShipments(gomock.Any(), 30, size).Return(shipments, totalElements, nil).Times(1)

		got, err := newTestService(t, shipmentRepo).GetAvailableShipments(context.Background(), page, size)

		if err != nil {
			t.Fatalf("unexpected error: %v", err)
		}
		want := &dto.ShipmentPageDTO{Shipments: shipments, TotalElements: totalElements}
		if !reflect.DeepEqual(got, want) {
			t.Errorf("GetAvailableShipments() = %+v, want %+v", got, want)
		}
	})

	for _, invalidPage := range invalidPages {
		t.Run("rejects "+invalidPage.name+" without calling the repository", func(t *testing.T) {
			shipmentRepo := repomocks.NewMockShipmentRepository(gomock.NewController(t))
			shipmentRepo.EXPECT().GetAvailableShipments(gomock.Any(), gomock.Any(), gomock.Any()).Times(0)

			_, err := newTestService(t, shipmentRepo).GetAvailableShipments(context.Background(), invalidPage.page, invalidPage.size)

			if !errors.Is(err, ErrInvalidPage) {
				t.Errorf("err = %v, want %v", err, ErrInvalidPage)
			}
		})
	}
}

func TestShipmentServiceImpl_GetCourierActiveShipments(t *testing.T) {
	t.Run("returns the requested page of the courier's shipments in progress and the number of all of them", func(t *testing.T) {
		courierID := uuid.New()
		page := 1
		size := 20
		totalElements := int64(21)
		shipments := []*model.Shipment{testShipment(t, 7, model.InDelivery, model.HolderCourier, courierID)}
		shipmentRepo := repomocks.NewMockShipmentRepository(gomock.NewController(t))
		shipmentRepo.EXPECT().GetActiveShipmentsByCourier(gomock.Any(), courierID, 20, size).Return(shipments, totalElements, nil).Times(1)

		got, err := newTestService(t, shipmentRepo).GetCourierActiveShipments(context.Background(), courierID, page, size)

		if err != nil {
			t.Fatalf("unexpected error: %v", err)
		}
		want := &dto.ShipmentPageDTO{Shipments: shipments, TotalElements: totalElements}
		if !reflect.DeepEqual(got, want) {
			t.Errorf("GetCourierActiveShipments() = %+v, want %+v", got, want)
		}
	})

	for _, invalidPage := range invalidPages {
		t.Run("rejects "+invalidPage.name+" without calling the repository", func(t *testing.T) {
			shipmentRepo := repomocks.NewMockShipmentRepository(gomock.NewController(t))
			shipmentRepo.EXPECT().GetActiveShipmentsByCourier(gomock.Any(), gomock.Any(), gomock.Any(), gomock.Any()).Times(0)

			_, err := newTestService(t, shipmentRepo).GetCourierActiveShipments(context.Background(), uuid.New(), invalidPage.page, invalidPage.size)

			if !errors.Is(err, ErrInvalidPage) {
				t.Errorf("err = %v, want %v", err, ErrInvalidPage)
			}
		})
	}
}

func TestShipmentServiceImpl_CreateShipment(t *testing.T) {
	msg := idempotencydto.KafkaMessageMetadata{MessageID: uuid.New(), CorrelationID: "corr-1", MessageType: "CreateShipment"}

	t.Run("persists a new shipment via the repository, then publishes the status change", func(t *testing.T) {
		orderID := uint64(100)
		address := testAddress(t)
		persisted := testShipment(t, 1, model.Created, model.HolderInventory, uuid.Nil)
		shipmentRepo := repomocks.NewMockShipmentRepository(gomock.NewController(t))
		producer := kafkamocks.NewMockShipmentKafkaProducer(gomock.NewController(t))
		processedMessageStore := idempotencymocks.NewMockProcessedKafkaMessageStore(gomock.NewController(t))
		processedMessageStore.EXPECT().MarkProcessed(gomock.Any(), msg).Return(false, nil).Times(1)

		shipmentRepo.EXPECT().Create(gomock.Any(), gomock.Any()).Times(1).DoAndReturn(
			func(ctx context.Context, s *model.Shipment) (*model.Shipment, error) {
				if s.OrderId() != orderID {
					t.Errorf("Create() shipment.OrderId() = %v, want %v", s.OrderId(), orderID)
				}
				if s.Address() != address {
					t.Errorf("Create() shipment.Address() = %+v, want %+v", s.Address(), address)
				}
				return persisted, nil
			},
		)
		producer.EXPECT().PublishShipmentStatusChanged(gomock.Any(), msg.CorrelationID, persisted).Return(nil).Times(1)

		service := NewShipmentServiceImpl(shipmentRepo, producer, processedMessageStore, noopTxExecutor{})

		got, err := service.CreateShipment(context.Background(), msg, orderID, address)

		if err != nil {
			t.Fatalf("unexpected error: %v", err)
		}
		if !reflect.DeepEqual(got, persisted) {
			t.Errorf("CreateShipment() = %+v, want %+v", got, persisted)
		}
	})

	t.Run("skips reprocessing an already-processed message without error", func(t *testing.T) {
		orderID := uint64(100)
		address := testAddress(t)
		shipmentRepo := repomocks.NewMockShipmentRepository(gomock.NewController(t))
		producer := kafkamocks.NewMockShipmentKafkaProducer(gomock.NewController(t))
		processedMessageStore := idempotencymocks.NewMockProcessedKafkaMessageStore(gomock.NewController(t))
		processedMessageStore.EXPECT().MarkProcessed(gomock.Any(), msg).Return(true, nil).Times(1)
		shipmentRepo.EXPECT().Create(gomock.Any(), gomock.Any()).Times(0)
		producer.EXPECT().PublishShipmentStatusChanged(gomock.Any(), gomock.Any(), gomock.Any()).Times(0)

		service := NewShipmentServiceImpl(shipmentRepo, producer, processedMessageStore, noopTxExecutor{})

		got, err := service.CreateShipment(context.Background(), msg, orderID, address)

		if err != nil {
			t.Fatalf("unexpected error: %v", err)
		}
		if got != nil {
			t.Errorf("got = %+v, want nil", got)
		}
	})

	t.Run("returns the original error without publishing when persisting fails", func(t *testing.T) {
		orderID := uint64(100)
		address := testAddress(t)
		wantErr := errors.New("db down")
		shipmentRepo := repomocks.NewMockShipmentRepository(gomock.NewController(t))
		producer := kafkamocks.NewMockShipmentKafkaProducer(gomock.NewController(t))
		processedMessageStore := idempotencymocks.NewMockProcessedKafkaMessageStore(gomock.NewController(t))
		processedMessageStore.EXPECT().MarkProcessed(gomock.Any(), msg).Return(false, nil).Times(1)

		shipmentRepo.EXPECT().Create(gomock.Any(), gomock.Any()).Return(nil, wantErr).Times(1)
		producer.EXPECT().PublishShipmentStatusChanged(gomock.Any(), gomock.Any(), gomock.Any()).Times(0)

		service := NewShipmentServiceImpl(shipmentRepo, producer, processedMessageStore, noopTxExecutor{})

		_, err := service.CreateShipment(context.Background(), msg, orderID, address)

		if !errors.Is(err, wantErr) {
			t.Errorf("err = %v, want %v", err, wantErr)
		}
	})

	t.Run("propagates an error publishing the status change after a successful create", func(t *testing.T) {
		orderID := uint64(100)
		address := testAddress(t)
		wantErr := errors.New("kafka down")
		persisted := testShipment(t, 1, model.Created, model.HolderInventory, uuid.Nil)
		shipmentRepo := repomocks.NewMockShipmentRepository(gomock.NewController(t))
		producer := kafkamocks.NewMockShipmentKafkaProducer(gomock.NewController(t))
		processedMessageStore := idempotencymocks.NewMockProcessedKafkaMessageStore(gomock.NewController(t))
		processedMessageStore.EXPECT().MarkProcessed(gomock.Any(), msg).Return(false, nil).Times(1)

		shipmentRepo.EXPECT().Create(gomock.Any(), gomock.Any()).Return(persisted, nil).Times(1)
		producer.EXPECT().PublishShipmentStatusChanged(gomock.Any(), msg.CorrelationID, persisted).Return(wantErr).Times(1)

		service := NewShipmentServiceImpl(shipmentRepo, producer, processedMessageStore, noopTxExecutor{})

		_, err := service.CreateShipment(context.Background(), msg, orderID, address)

		if !errors.Is(err, wantErr) {
			t.Errorf("err = %v, want %v", err, wantErr)
		}
	})
}

func TestShipmentServiceImpl_StartDelivery(t *testing.T) {
	t.Run("starts delivery, persists, and publishes the status change", func(t *testing.T) {
		id := uint64(1)
		courierID := uuid.New()
		shipment := testShipment(t, id, model.Created, model.HolderInventory, uuid.Nil)
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

		service := NewShipmentServiceImpl(shipmentRepo, producer, idempotencymocks.NewMockProcessedKafkaMessageStore(gomock.NewController(t)), noopTxExecutor{})

		got, err := service.StartDelivery(context.Background(), id, courierID)

		if err != nil {
			t.Fatalf("unexpected error: %v", err)
		}
		if transitioned == nil || transitioned.Status() != model.InDelivery || transitioned.Holder() != model.HolderCourier ||
			transitioned.CourierID() != courierID {
			t.Fatalf("unexpected transition: %+v", transitioned)
		}
		if !reflect.DeepEqual(got, transitioned) {
			t.Errorf("StartDelivery() = %+v, want %+v", got, transitioned)
		}
	})

	t.Run("publishes the status change with the correlation id the request came with", func(t *testing.T) {
		id := uint64(1)
		shipment := testShipment(t, id, model.Created, model.HolderInventory, uuid.Nil)
		shipmentRepo := repomocks.NewMockShipmentRepository(gomock.NewController(t))
		producer := kafkamocks.NewMockShipmentKafkaProducer(gomock.NewController(t))

		shipmentRepo.EXPECT().GetByID(gomock.Any(), id).Return(shipment, nil).Times(1)
		shipmentRepo.EXPECT().Update(gomock.Any(), gomock.Any()).
			DoAndReturn(func(ctx context.Context, s *model.Shipment) (*model.Shipment, error) { return s, nil }).Times(1)
		correlationID := "corr-from-request"
		producer.EXPECT().PublishShipmentStatusChanged(gomock.Any(), correlationID, gomock.Any()).Return(nil).Times(1)

		service := NewShipmentServiceImpl(shipmentRepo, producer, idempotencymocks.NewMockProcessedKafkaMessageStore(gomock.NewController(t)), noopTxExecutor{})

		_, err := service.StartDelivery(logging.WithCorrelationID(context.Background(), correlationID), id, uuid.New())

		if err != nil {
			t.Fatalf("unexpected error: %v", err)
		}
	})

	t.Run("logs the rest of a request that came without a correlation id with the one derived from the order", func(t *testing.T) {
		id := uint64(1)
		shipment := testShipment(t, id, model.Created, model.HolderInventory, uuid.Nil)
		shipmentRepo := repomocks.NewMockShipmentRepository(gomock.NewController(t))
		producer := kafkamocks.NewMockShipmentKafkaProducer(gomock.NewController(t))

		shipmentRepo.EXPECT().GetByID(gomock.Any(), id).Return(shipment, nil).Times(1)
		shipmentRepo.EXPECT().Update(gomock.Any(), gomock.Any()).
			DoAndReturn(func(ctx context.Context, s *model.Shipment) (*model.Shipment, error) { return s, nil }).Times(1)
		correlationID := "100"
		producer.EXPECT().PublishShipmentStatusChanged(gomock.Any(), correlationID, gomock.Any()).Return(nil).Times(1)

		service := NewShipmentServiceImpl(shipmentRepo, producer, idempotencymocks.NewMockProcessedKafkaMessageStore(gomock.NewController(t)), noopTxExecutor{})
		requestCtx := logging.WithCorrelationID(context.Background(), "")

		_, err := service.StartDelivery(requestCtx, id, uuid.New())

		if err != nil {
			t.Fatalf("unexpected error: %v", err)
		}
		wantAttrs := []slog.Attr{slog.String(logging.CorrelationIDKey, correlationID)}
		if gotAttrs := logging.Attrs(requestCtx); !slices.EqualFunc(gotAttrs, wantAttrs, slog.Attr.Equal) {
			t.Errorf("logging.Attrs(request) = %v, want %v", gotAttrs, wantAttrs)
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

		service := NewShipmentServiceImpl(shipmentRepo, producer, idempotencymocks.NewMockProcessedKafkaMessageStore(gomock.NewController(t)), noopTxExecutor{})

		_, err := service.StartDelivery(context.Background(), id, uuid.New())

		if !errors.Is(err, wantErr) {
			t.Errorf("err = %v, want %v", err, wantErr)
		}
	})

	t.Run("propagates a domain error when the shipment is not in a startable status", func(t *testing.T) {
		id := uint64(1)
		shipment := testShipment(t, id, model.Delivered, model.HolderCourier, uuid.New()) // already terminal
		shipmentRepo := repomocks.NewMockShipmentRepository(gomock.NewController(t))
		producer := kafkamocks.NewMockShipmentKafkaProducer(gomock.NewController(t))

		shipmentRepo.EXPECT().GetByID(gomock.Any(), id).Return(shipment, nil).Times(1)
		shipmentRepo.EXPECT().Update(gomock.Any(), gomock.Any()).Times(0)
		producer.EXPECT().PublishShipmentStatusChanged(gomock.Any(), gomock.Any(), gomock.Any()).Times(0)

		service := NewShipmentServiceImpl(shipmentRepo, producer, idempotencymocks.NewMockProcessedKafkaMessageStore(gomock.NewController(t)), noopTxExecutor{})

		_, err := service.StartDelivery(context.Background(), id, uuid.New())

		if !errors.Is(err, model.ErrInvalidStatusTransition) {
			t.Errorf("err = %v, want %v", err, model.ErrInvalidStatusTransition)
		}
	})

	t.Run("reports a shipment another courier picked up meanwhile as already picked up", func(t *testing.T) {
		id := uint64(1)
		shipment := testShipment(t, id, model.Created, model.HolderInventory, uuid.Nil)
		shipmentRepo := repomocks.NewMockShipmentRepository(gomock.NewController(t))
		producer := kafkamocks.NewMockShipmentKafkaProducer(gomock.NewController(t))

		shipmentRepo.EXPECT().GetByID(gomock.Any(), id).Return(shipment, nil).Times(1)
		shipmentRepo.EXPECT().Update(gomock.Any(), gomock.Any()).Return(nil, repository.ErrConcurrentUpdate).Times(1)
		producer.EXPECT().PublishShipmentStatusChanged(gomock.Any(), gomock.Any(), gomock.Any()).Times(0)

		service := NewShipmentServiceImpl(shipmentRepo, producer, idempotencymocks.NewMockProcessedKafkaMessageStore(gomock.NewController(t)), noopTxExecutor{})

		_, err := service.StartDelivery(context.Background(), id, uuid.New())

		// the outermost sentinel decides how the error is reported, and the concurrent update stays in the chain
		if !apperror.OutermostError(err).Is(ErrAlreadyPickedUp) {
			t.Errorf("err = %v, want %v outermost", err, ErrAlreadyPickedUp)
		}
		if !errors.Is(err, repository.ErrConcurrentUpdate) {
			t.Errorf("err = %v, want it to wrap %v", err, repository.ErrConcurrentUpdate)
		}
	})

	t.Run("propagates an error persisting the transitioned shipment", func(t *testing.T) {
		id := uint64(1)
		wantErr := errors.New("db down")
		shipment := testShipment(t, id, model.Created, model.HolderInventory, uuid.Nil)
		shipmentRepo := repomocks.NewMockShipmentRepository(gomock.NewController(t))
		producer := kafkamocks.NewMockShipmentKafkaProducer(gomock.NewController(t))

		shipmentRepo.EXPECT().GetByID(gomock.Any(), id).Return(shipment, nil).Times(1)
		shipmentRepo.EXPECT().Update(gomock.Any(), gomock.Any()).Return(nil, wantErr).Times(1)
		producer.EXPECT().PublishShipmentStatusChanged(gomock.Any(), gomock.Any(), gomock.Any()).Times(0)

		service := NewShipmentServiceImpl(shipmentRepo, producer, idempotencymocks.NewMockProcessedKafkaMessageStore(gomock.NewController(t)), noopTxExecutor{})

		_, err := service.StartDelivery(context.Background(), id, uuid.New())

		if !errors.Is(err, wantErr) {
			t.Errorf("err = %v, want %v", err, wantErr)
		}
	})

	t.Run("propagates an error publishing the status change", func(t *testing.T) {
		id := uint64(1)
		wantErr := errors.New("kafka down")
		shipment := testShipment(t, id, model.Created, model.HolderInventory, uuid.Nil)
		updated := testShipment(t, id, model.InDelivery, model.HolderCourier, uuid.New())
		shipmentRepo := repomocks.NewMockShipmentRepository(gomock.NewController(t))
		producer := kafkamocks.NewMockShipmentKafkaProducer(gomock.NewController(t))

		shipmentRepo.EXPECT().GetByID(gomock.Any(), id).Return(shipment, nil).Times(1)
		shipmentRepo.EXPECT().Update(gomock.Any(), gomock.Any()).Return(updated, nil).Times(1)
		producer.EXPECT().PublishShipmentStatusChanged(gomock.Any(), "100", updated).Return(wantErr).Times(1)

		service := NewShipmentServiceImpl(shipmentRepo, producer, idempotencymocks.NewMockProcessedKafkaMessageStore(gomock.NewController(t)), noopTxExecutor{})

		_, err := service.StartDelivery(context.Background(), id, uuid.New())

		if !errors.Is(err, wantErr) {
			t.Errorf("err = %v, want %v", err, wantErr)
		}
	})
}

func TestShipmentServiceImpl_Deliver(t *testing.T) {
	t.Run("delivers the shipment for the assigned courier, persists, and publishes the status change", func(t *testing.T) {
		id := uint64(1)
		courierID := uuid.New()
		shipment := testShipment(t, id, model.InDelivery, model.HolderCourier, courierID)
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

		service := NewShipmentServiceImpl(shipmentRepo, producer, idempotencymocks.NewMockProcessedKafkaMessageStore(gomock.NewController(t)), noopTxExecutor{})

		got, err := service.Deliver(context.Background(), id, courierID)

		if err != nil {
			t.Fatalf("unexpected error: %v", err)
		}
		if transitioned == nil || transitioned.Status() != model.Delivered || transitioned.Holder() != model.HolderCourier {
			t.Fatalf("unexpected transition: %+v", transitioned)
		}
		if !reflect.DeepEqual(got, transitioned) {
			t.Errorf("Deliver() = %+v, want %+v", got, transitioned)
		}
	})

	t.Run("rejects a courier the shipment is not assigned to, without persisting or publishing", func(t *testing.T) {
		id := uint64(1)
		shipment := testShipment(t, id, model.InDelivery, model.HolderCourier, uuid.New())
		shipmentRepo := repomocks.NewMockShipmentRepository(gomock.NewController(t))
		producer := kafkamocks.NewMockShipmentKafkaProducer(gomock.NewController(t))

		shipmentRepo.EXPECT().GetByID(gomock.Any(), id).Return(shipment, nil).Times(1)
		shipmentRepo.EXPECT().Update(gomock.Any(), gomock.Any()).Times(0)
		producer.EXPECT().PublishShipmentStatusChanged(gomock.Any(), gomock.Any(), gomock.Any()).Times(0)

		service := NewShipmentServiceImpl(shipmentRepo, producer, idempotencymocks.NewMockProcessedKafkaMessageStore(gomock.NewController(t)), noopTxExecutor{})

		_, err := service.Deliver(context.Background(), id, uuid.New())

		if !errors.Is(err, ErrNotAssignedCourier) {
			t.Errorf("err = %v, want %v", err, ErrNotAssignedCourier)
		}
		if shipment.Status() != model.InDelivery {
			t.Errorf("rejected transition changed the status to %v", shipment.Status())
		}
	})
}

func TestShipmentServiceImpl_Lose(t *testing.T) {
	t.Run("reports lost the shipment for the assigned courier, persists, and publishes the status change", func(t *testing.T) {
		id := uint64(1)
		courierID := uuid.New()
		shipment := testShipment(t, id, model.InDelivery, model.HolderCourier, courierID)
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

		service := NewShipmentServiceImpl(shipmentRepo, producer, idempotencymocks.NewMockProcessedKafkaMessageStore(gomock.NewController(t)), noopTxExecutor{})

		got, err := service.Lose(context.Background(), id, courierID)

		if err != nil {
			t.Fatalf("unexpected error: %v", err)
		}
		if transitioned == nil || transitioned.Status() != model.Lost || transitioned.Holder() != model.HolderCourier {
			t.Fatalf("unexpected transition: %+v", transitioned)
		}
		if !reflect.DeepEqual(got, transitioned) {
			t.Errorf("Lose() = %+v, want %+v", got, transitioned)
		}
	})

	t.Run("rejects a courier the shipment is not assigned to, without persisting or publishing", func(t *testing.T) {
		id := uint64(1)
		shipment := testShipment(t, id, model.InDelivery, model.HolderCourier, uuid.New())
		shipmentRepo := repomocks.NewMockShipmentRepository(gomock.NewController(t))
		producer := kafkamocks.NewMockShipmentKafkaProducer(gomock.NewController(t))

		shipmentRepo.EXPECT().GetByID(gomock.Any(), id).Return(shipment, nil).Times(1)
		shipmentRepo.EXPECT().Update(gomock.Any(), gomock.Any()).Times(0)
		producer.EXPECT().PublishShipmentStatusChanged(gomock.Any(), gomock.Any(), gomock.Any()).Times(0)

		service := NewShipmentServiceImpl(shipmentRepo, producer, idempotencymocks.NewMockProcessedKafkaMessageStore(gomock.NewController(t)), noopTxExecutor{})

		_, err := service.Lose(context.Background(), id, uuid.New())

		if !errors.Is(err, ErrNotAssignedCourier) {
			t.Errorf("err = %v, want %v", err, ErrNotAssignedCourier)
		}
		if shipment.Status() != model.InDelivery {
			t.Errorf("rejected transition changed the status to %v", shipment.Status())
		}
	})
}

func TestShipmentServiceImpl_Cancel(t *testing.T) {
	t.Run("cancels the shipment for the assigned courier with their reason, persists, and publishes the status change", func(t *testing.T) {
		id := uint64(1)
		courierID := uuid.New()
		reason := model.RecipientUnavailable
		shipment := testShipment(t, id, model.InDelivery, model.HolderCourier, courierID)
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

		service := NewShipmentServiceImpl(shipmentRepo, producer, idempotencymocks.NewMockProcessedKafkaMessageStore(gomock.NewController(t)), noopTxExecutor{})

		got, err := service.Cancel(context.Background(), id, courierID, reason)

		if err != nil {
			t.Fatalf("unexpected error: %v", err)
		}
		if transitioned == nil || transitioned.Status() != model.Cancelled || transitioned.Holder() != model.HolderCourier {
			t.Fatalf("unexpected transition: %+v", transitioned)
		}
		if transitioned.CancellationReason() != reason {
			t.Errorf("CancellationReason() = %q, want %q", transitioned.CancellationReason(), reason)
		}
		if !reflect.DeepEqual(got, transitioned) {
			t.Errorf("Cancel() = %+v, want %+v", got, transitioned)
		}
	})

	for _, reason := range []model.CancellationReason{model.OrderCancelled, model.NoCancellationReason, "Whatever"} {
		t.Run("rejects the reason \""+string(reason)+"\", which a courier may not report, without loading the shipment", func(t *testing.T) {
			shipmentRepo := repomocks.NewMockShipmentRepository(gomock.NewController(t))
			producer := kafkamocks.NewMockShipmentKafkaProducer(gomock.NewController(t))
			shipmentRepo.EXPECT().GetByID(gomock.Any(), gomock.Any()).Times(0)
			shipmentRepo.EXPECT().Update(gomock.Any(), gomock.Any()).Times(0)
			producer.EXPECT().PublishShipmentStatusChanged(gomock.Any(), gomock.Any(), gomock.Any()).Times(0)

			service := NewShipmentServiceImpl(shipmentRepo, producer, idempotencymocks.NewMockProcessedKafkaMessageStore(gomock.NewController(t)), noopTxExecutor{})

			_, err := service.Cancel(context.Background(), 1, uuid.New(), reason)

			if !errors.Is(err, ErrInvalidCancellationReason) {
				t.Errorf("err = %v, want %v", err, ErrInvalidCancellationReason)
			}
		})
	}

	t.Run("rejects a courier the shipment is not assigned to, without persisting or publishing", func(t *testing.T) {
		id := uint64(1)
		shipment := testShipment(t, id, model.InDelivery, model.HolderCourier, uuid.New())
		shipmentRepo := repomocks.NewMockShipmentRepository(gomock.NewController(t))
		producer := kafkamocks.NewMockShipmentKafkaProducer(gomock.NewController(t))

		shipmentRepo.EXPECT().GetByID(gomock.Any(), id).Return(shipment, nil).Times(1)
		shipmentRepo.EXPECT().Update(gomock.Any(), gomock.Any()).Times(0)
		producer.EXPECT().PublishShipmentStatusChanged(gomock.Any(), gomock.Any(), gomock.Any()).Times(0)

		service := NewShipmentServiceImpl(shipmentRepo, producer, idempotencymocks.NewMockProcessedKafkaMessageStore(gomock.NewController(t)), noopTxExecutor{})

		_, err := service.Cancel(context.Background(), id, uuid.New(), model.RecipientRefused)

		if !errors.Is(err, ErrNotAssignedCourier) {
			t.Errorf("err = %v, want %v", err, ErrNotAssignedCourier)
		}
		if shipment.Status() != model.InDelivery {
			t.Errorf("Status() = %v, want %v", shipment.Status(), model.InDelivery)
		}
	})
}

func TestShipmentServiceImpl_CancelByOrder(t *testing.T) {
	msg := idempotencydto.KafkaMessageMetadata{MessageID: uuid.New(), CorrelationID: "corr-1", MessageType: "CancelShipment"}

	t.Run("cancels the order's shipment with the order, persists, and publishes the status change", func(t *testing.T) {
		orderID := uint64(100)
		courierID := uuid.New()
		shipment := testShipment(t, 1, model.InDelivery, model.HolderCourier, courierID)
		shipmentRepo := repomocks.NewMockShipmentRepository(gomock.NewController(t))
		producer := kafkamocks.NewMockShipmentKafkaProducer(gomock.NewController(t))
		processedMessageStore := idempotencymocks.NewMockProcessedKafkaMessageStore(gomock.NewController(t))
		processedMessageStore.EXPECT().MarkProcessed(gomock.Any(), msg).Return(false, nil).Times(1)
		shipmentRepo.EXPECT().GetByOrderID(gomock.Any(), orderID).Return(shipment, nil).Times(1)

		var transitioned *model.Shipment
		shipmentRepo.EXPECT().Update(gomock.Any(), gomock.Any()).Times(1).DoAndReturn(
			func(ctx context.Context, s *model.Shipment) (*model.Shipment, error) {
				transitioned = s
				return s, nil
			},
		)
		producer.EXPECT().PublishShipmentStatusChanged(gomock.Any(), msg.CorrelationID, gomock.Any()).Return(nil).Times(1)

		service := NewShipmentServiceImpl(shipmentRepo, producer, processedMessageStore, noopTxExecutor{})

		got, err := service.CancelByOrder(context.Background(), msg, orderID)

		if err != nil {
			t.Fatalf("unexpected error: %v", err)
		}
		if !got {
			t.Error("CancelByOrder() = false, want true")
		}
		if transitioned == nil || transitioned.Status() != model.Cancelled || transitioned.Holder() != model.HolderCourier {
			t.Fatalf("unexpected transition: %+v", transitioned)
		}
		if transitioned.CancellationReason() != model.OrderCancelled {
			t.Errorf("CancellationReason() = %q, want %q", transitioned.CancellationReason(), model.OrderCancelled)
		}
	})

	t.Run("skips reprocessing an already-processed message without error", func(t *testing.T) {
		shipmentRepo := repomocks.NewMockShipmentRepository(gomock.NewController(t))
		producer := kafkamocks.NewMockShipmentKafkaProducer(gomock.NewController(t))
		processedMessageStore := idempotencymocks.NewMockProcessedKafkaMessageStore(gomock.NewController(t))
		processedMessageStore.EXPECT().MarkProcessed(gomock.Any(), msg).Return(true, nil).Times(1)
		shipmentRepo.EXPECT().GetByOrderID(gomock.Any(), gomock.Any()).Times(0)
		shipmentRepo.EXPECT().Update(gomock.Any(), gomock.Any()).Times(0)
		producer.EXPECT().PublishShipmentStatusChanged(gomock.Any(), gomock.Any(), gomock.Any()).Times(0)

		service := NewShipmentServiceImpl(shipmentRepo, producer, processedMessageStore, noopTxExecutor{})

		got, err := service.CancelByOrder(context.Background(), msg, 100)

		if err != nil {
			t.Fatalf("unexpected error: %v", err)
		}
		if got {
			t.Error("CancelByOrder() = true, want false")
		}
	})

	t.Run("drops the request without persisting or publishing when the shipment has already ended", func(t *testing.T) {
		orderID := uint64(100)
		shipment := testShipment(t, 1, model.Delivered, model.HolderCourier, uuid.New())
		shipmentRepo := repomocks.NewMockShipmentRepository(gomock.NewController(t))
		producer := kafkamocks.NewMockShipmentKafkaProducer(gomock.NewController(t))
		processedMessageStore := idempotencymocks.NewMockProcessedKafkaMessageStore(gomock.NewController(t))
		processedMessageStore.EXPECT().MarkProcessed(gomock.Any(), msg).Return(false, nil).Times(1)
		shipmentRepo.EXPECT().GetByOrderID(gomock.Any(), orderID).Return(shipment, nil).Times(1)
		shipmentRepo.EXPECT().Update(gomock.Any(), gomock.Any()).Times(0)
		producer.EXPECT().PublishShipmentStatusChanged(gomock.Any(), gomock.Any(), gomock.Any()).Times(0)

		service := NewShipmentServiceImpl(shipmentRepo, producer, processedMessageStore, noopTxExecutor{})

		got, err := service.CancelByOrder(context.Background(), msg, orderID)

		if err != nil {
			t.Fatalf("unexpected error: %v", err)
		}
		if got {
			t.Error("CancelByOrder() = true, want false")
		}
		if shipment.Status() != model.Delivered {
			t.Errorf("Status() = %v, want %v", shipment.Status(), model.Delivered)
		}
	})

	t.Run("propagates an error fetching the order's shipment", func(t *testing.T) {
		orderID := uint64(100)
		shipmentRepo := repomocks.NewMockShipmentRepository(gomock.NewController(t))
		producer := kafkamocks.NewMockShipmentKafkaProducer(gomock.NewController(t))
		processedMessageStore := idempotencymocks.NewMockProcessedKafkaMessageStore(gomock.NewController(t))
		processedMessageStore.EXPECT().MarkProcessed(gomock.Any(), msg).Return(false, nil).Times(1)
		shipmentRepo.EXPECT().GetByOrderID(gomock.Any(), orderID).Return(nil, repository.ErrShipmentNotFound).Times(1)
		shipmentRepo.EXPECT().Update(gomock.Any(), gomock.Any()).Times(0)
		producer.EXPECT().PublishShipmentStatusChanged(gomock.Any(), gomock.Any(), gomock.Any()).Times(0)

		service := NewShipmentServiceImpl(shipmentRepo, producer, processedMessageStore, noopTxExecutor{})

		_, err := service.CancelByOrder(context.Background(), msg, orderID)

		if !errors.Is(err, repository.ErrShipmentNotFound) {
			t.Errorf("err = %v, want %v", err, repository.ErrShipmentNotFound)
		}
	})

	t.Run("propagates an error persisting the cancelled shipment, without publishing", func(t *testing.T) {
		orderID := uint64(100)
		shipment := testShipment(t, 1, model.Created, model.HolderInventory, uuid.Nil)
		shipmentRepo := repomocks.NewMockShipmentRepository(gomock.NewController(t))
		producer := kafkamocks.NewMockShipmentKafkaProducer(gomock.NewController(t))
		processedMessageStore := idempotencymocks.NewMockProcessedKafkaMessageStore(gomock.NewController(t))
		processedMessageStore.EXPECT().MarkProcessed(gomock.Any(), msg).Return(false, nil).Times(1)
		shipmentRepo.EXPECT().GetByOrderID(gomock.Any(), orderID).Return(shipment, nil).Times(1)
		shipmentRepo.EXPECT().Update(gomock.Any(), gomock.Any()).Return(nil, repository.ErrConcurrentUpdate).Times(1)
		producer.EXPECT().PublishShipmentStatusChanged(gomock.Any(), gomock.Any(), gomock.Any()).Times(0)

		service := NewShipmentServiceImpl(shipmentRepo, producer, processedMessageStore, noopTxExecutor{})

		_, err := service.CancelByOrder(context.Background(), msg, orderID)

		if !errors.Is(err, repository.ErrConcurrentUpdate) {
			t.Errorf("err = %v, want %v", err, repository.ErrConcurrentUpdate)
		}
	})
}

func TestShipmentServiceImpl_ConfirmReturnedToInventory(t *testing.T) {
	t.Run("moves the holder back to inventory and publishes the returned event", func(t *testing.T) {
		id := uint64(1)
		courierID := uuid.New()
		shipment := testShipment(t, id, model.Cancelled, model.HolderCourier, courierID)
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

		service := NewShipmentServiceImpl(shipmentRepo, producer, idempotencymocks.NewMockProcessedKafkaMessageStore(gomock.NewController(t)), noopTxExecutor{})

		got, err := service.ConfirmReturnedToInventory(context.Background(), id, courierID)

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

		service := NewShipmentServiceImpl(shipmentRepo, producer, idempotencymocks.NewMockProcessedKafkaMessageStore(gomock.NewController(t)), noopTxExecutor{})

		_, err := service.ConfirmReturnedToInventory(context.Background(), id, uuid.New())

		if !errors.Is(err, wantErr) {
			t.Errorf("err = %v, want %v", err, wantErr)
		}
	})

	t.Run("propagates a domain error when the goods are already back at the inventory", func(t *testing.T) {
		id := uint64(1)
		courierID := uuid.New()
		shipment := testShipment(t, id, model.Cancelled, model.HolderInventory, courierID)
		shipmentRepo := repomocks.NewMockShipmentRepository(gomock.NewController(t))
		producer := kafkamocks.NewMockShipmentKafkaProducer(gomock.NewController(t))

		shipmentRepo.EXPECT().GetByID(gomock.Any(), id).Return(shipment, nil).Times(1)
		shipmentRepo.EXPECT().Update(gomock.Any(), gomock.Any()).Times(0)
		producer.EXPECT().PublishShipmentReturnedToInventory(gomock.Any(), gomock.Any(), gomock.Any()).Times(0)

		service := NewShipmentServiceImpl(shipmentRepo, producer, idempotencymocks.NewMockProcessedKafkaMessageStore(gomock.NewController(t)), noopTxExecutor{})

		_, err := service.ConfirmReturnedToInventory(context.Background(), id, courierID)

		if !errors.Is(err, model.ErrInvalidStatusTransition) {
			t.Errorf("err = %v, want %v", err, model.ErrInvalidStatusTransition)
		}
	})

	t.Run("rejects a courier the shipment is not assigned to, without persisting or publishing", func(t *testing.T) {
		id := uint64(1)
		shipment := testShipment(t, id, model.Cancelled, model.HolderCourier, uuid.New())
		shipmentRepo := repomocks.NewMockShipmentRepository(gomock.NewController(t))
		producer := kafkamocks.NewMockShipmentKafkaProducer(gomock.NewController(t))

		shipmentRepo.EXPECT().GetByID(gomock.Any(), id).Return(shipment, nil).Times(1)
		shipmentRepo.EXPECT().Update(gomock.Any(), gomock.Any()).Times(0)
		producer.EXPECT().PublishShipmentReturnedToInventory(gomock.Any(), gomock.Any(), gomock.Any()).Times(0)

		service := NewShipmentServiceImpl(shipmentRepo, producer, idempotencymocks.NewMockProcessedKafkaMessageStore(gomock.NewController(t)), noopTxExecutor{})

		_, err := service.ConfirmReturnedToInventory(context.Background(), id, uuid.New())

		if !errors.Is(err, ErrNotAssignedCourier) {
			t.Errorf("err = %v, want %v", err, ErrNotAssignedCourier)
		}
		if shipment.Holder() != model.HolderCourier {
			t.Errorf("rejected transition changed the holder to %v", shipment.Holder())
		}
	})

	t.Run("propagates an error persisting the transitioned shipment", func(t *testing.T) {
		id := uint64(1)
		wantErr := errors.New("db down")
		courierID := uuid.New()
		shipment := testShipment(t, id, model.Cancelled, model.HolderCourier, courierID)
		shipmentRepo := repomocks.NewMockShipmentRepository(gomock.NewController(t))
		producer := kafkamocks.NewMockShipmentKafkaProducer(gomock.NewController(t))

		shipmentRepo.EXPECT().GetByID(gomock.Any(), id).Return(shipment, nil).Times(1)
		shipmentRepo.EXPECT().Update(gomock.Any(), gomock.Any()).Return(nil, wantErr).Times(1)
		producer.EXPECT().PublishShipmentReturnedToInventory(gomock.Any(), gomock.Any(), gomock.Any()).Times(0)

		service := NewShipmentServiceImpl(shipmentRepo, producer, idempotencymocks.NewMockProcessedKafkaMessageStore(gomock.NewController(t)), noopTxExecutor{})

		_, err := service.ConfirmReturnedToInventory(context.Background(), id, courierID)

		if !errors.Is(err, wantErr) {
			t.Errorf("err = %v, want %v", err, wantErr)
		}
	})

	t.Run("propagates an error publishing the returned-to-inventory event", func(t *testing.T) {
		id := uint64(1)
		wantErr := errors.New("kafka down")
		courierID := uuid.New()
		shipment := testShipment(t, id, model.Cancelled, model.HolderCourier, courierID)
		updated := testShipment(t, id, model.Cancelled, model.HolderInventory, courierID)
		shipmentRepo := repomocks.NewMockShipmentRepository(gomock.NewController(t))
		producer := kafkamocks.NewMockShipmentKafkaProducer(gomock.NewController(t))

		shipmentRepo.EXPECT().GetByID(gomock.Any(), id).Return(shipment, nil).Times(1)
		shipmentRepo.EXPECT().Update(gomock.Any(), gomock.Any()).Return(updated, nil).Times(1)
		producer.EXPECT().PublishShipmentReturnedToInventory(gomock.Any(), "100", updated).Return(wantErr).Times(1)

		service := NewShipmentServiceImpl(shipmentRepo, producer, idempotencymocks.NewMockProcessedKafkaMessageStore(gomock.NewController(t)), noopTxExecutor{})

		_, err := service.ConfirmReturnedToInventory(context.Background(), id, courierID)

		if !errors.Is(err, wantErr) {
			t.Errorf("err = %v, want %v", err, wantErr)
		}
	})
}
