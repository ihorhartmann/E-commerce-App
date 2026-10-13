package application

import (
	"context"
	"errors"
	"slices"
	"testing"

	"go.uber.org/mock/gomock"

	"inventoryservice/application/dto"
	idempotencydto "inventoryservice/application/port/out/idempotency/dto"
	idempotencymocks "inventoryservice/application/port/out/idempotency/mocks"
	"inventoryservice/application/port/out/repository"
	"inventoryservice/application/port/out/repository/mocks"
	"inventoryservice/domain/model"
	"inventoryservice/domain/values"

	"github.com/google/uuid"

	"github.com/shopspring/decimal"
)

func testMoney(t *testing.T) values.Money {
	t.Helper()
	money, err := values.NewMoney(decimal.NewFromInt(10), values.EUR)
	if err != nil {
		t.Fatalf("unexpected error building test money: %v", err)
	}
	return money
}

// testProduct builds a stored product with quantity on hand, of which reserved is reserved.
func testProduct(t *testing.T, id uint64, quantity, reserved uint) *model.Product {
	t.Helper()
	product, err := model.NewExistingProduct(id, "Widget", "a widget", testMoney(t), quantity, reserved, 1)
	if err != nil {
		t.Fatalf("unexpected error building test product: %v", err)
	}
	return product
}

// testNewProduct builds a not-yet-persisted product (id 0), as it exists before a repository
// assigns it a real id on Create.
func testNewProduct(t *testing.T, quantity uint) *model.Product {
	t.Helper()
	product, err := model.NewProduct(0, "Widget", "a widget", testMoney(t), quantity)
	if err != nil {
		t.Fatalf("unexpected error building test product: %v", err)
	}
	return product
}

func testReservation(t *testing.T, id, productID, orderID uint64, quantity uint, holder model.ReservationHolder) *model.StockReservation {
	t.Helper()
	reservation, err := model.NewExistingStockReservation(id, productID, orderID, quantity, holder, 0)
	if err != nil {
		t.Fatalf("unexpected error building test reservation: %v", err)
	}
	return reservation
}

func TestReservationServiceImpl_ReserveItems(t *testing.T) {
	t.Run("counts the reservations as reserved on the product, creates them and returns the stored line", func(t *testing.T) {
		productID := uint64(1)
		quantity := uint(4)
		product := testProduct(t, productID, 10, 0)
		wantProduct := testProduct(t, productID, 10, quantity) // what the repository returns after storing it
		productRepo := mocks.NewMockProductRepository(gomock.NewController(t))
		reservationRepo := mocks.NewMockReservationRepository(gomock.NewController(t))

		productRepo.EXPECT().GetByIDs(gomock.Any(), []uint64{productID}).Return([]*model.Product{product}, nil).Times(1)
		var gotUpdatedProduct *model.Product
		productRepo.EXPECT().Update(gomock.Any(), gomock.Any()).Times(1).DoAndReturn(
			func(ctx context.Context, updatedProduct *model.Product) (*model.Product, error) {
				gotUpdatedProduct = updatedProduct
				return wantProduct, nil
			},
		)
		var gotReservations []*model.StockReservation
		reservationRepo.EXPECT().CreateBatch(gomock.Any(), gomock.Any()).Times(1).DoAndReturn(
			func(ctx context.Context, reservations []*model.StockReservation) ([]*model.StockReservation, error) {
				gotReservations = reservations
				return reservations, nil
			},
		)

		service := NewReservationService(reservationRepo, productRepo, idempotencymocks.NewMockProcessedKafkaMessageStore(gomock.NewController(t)), noopTxExecutor{})

		gotItems, err := service.ReserveItems(context.Background(), 100, []dto.ReservationDTO{{ProductID: productID, Quantity: quantity}})

		if err != nil {
			t.Fatalf("unexpected error: %v", err)
		}
		if len(gotItems) != 1 || gotItems[0].Product != wantProduct || gotItems[0].Quantity != quantity {
			t.Errorf("ReserveItems() = %+v, want only %d of the stored product %+v", gotItems, quantity, wantProduct)
		}
		if len(gotReservations) != 1 || gotReservations[0].Quantity() != quantity || gotReservations[0].Holder() != model.ReservationHolderBuyer {
			t.Errorf("reservations passed to CreateBatch = %+v, want one of %d held by the buyer", gotReservations, quantity)
		}
		if gotUpdatedProduct.Reserved() != quantity {
			t.Errorf("product passed to Update has Reserved() = %d, want %d", gotUpdatedProduct.Reserved(), quantity)
		}
	})

	t.Run("stores the product once and returns every line when several lines reserve it", func(t *testing.T) {
		productID := uint64(1)
		firstQuantity := uint(2)
		secondQuantity := uint(3)
		product := testProduct(t, productID, 10, 0)
		productRepo := mocks.NewMockProductRepository(gomock.NewController(t))
		reservationRepo := mocks.NewMockReservationRepository(gomock.NewController(t))

		productRepo.EXPECT().GetByIDs(gomock.Any(), []uint64{productID, productID}).Return([]*model.Product{product}, nil).Times(1)
		productRepo.EXPECT().Update(gomock.Any(), product).Return(product, nil).Times(1)
		reservationRepo.EXPECT().CreateBatch(gomock.Any(), gomock.Any()).Times(1).DoAndReturn(
			func(ctx context.Context, reservations []*model.StockReservation) ([]*model.StockReservation, error) {
				return reservations, nil
			},
		)

		service := NewReservationService(reservationRepo, productRepo, idempotencymocks.NewMockProcessedKafkaMessageStore(gomock.NewController(t)), noopTxExecutor{})

		gotItems, err := service.ReserveItems(context.Background(), 100, []dto.ReservationDTO{
			{ProductID: productID, Quantity: firstQuantity},
			{ProductID: productID, Quantity: secondQuantity},
		})

		if err != nil {
			t.Fatalf("unexpected error: %v", err)
		}
		wantItems := []dto.ReservedItemDTO{
			{Product: product, Quantity: firstQuantity},
			{Product: product, Quantity: secondQuantity},
		}
		if !slices.Equal(gotItems, wantItems) {
			t.Errorf("ReserveItems() = %+v, want %+v", gotItems, wantItems)
		}
	})

	t.Run("fails without storing anything when the available stock is too low", func(t *testing.T) {
		productID := uint64(1)
		// 10 on hand, 8 already reserved by another order: only 2 left.
		product := testProduct(t, productID, 10, 8)
		productRepo := mocks.NewMockProductRepository(gomock.NewController(t))
		reservationRepo := mocks.NewMockReservationRepository(gomock.NewController(t))

		productRepo.EXPECT().GetByIDs(gomock.Any(), []uint64{productID}).Return([]*model.Product{product}, nil).Times(1)
		productRepo.EXPECT().Update(gomock.Any(), gomock.Any()).Times(0)
		reservationRepo.EXPECT().CreateBatch(gomock.Any(), gomock.Any()).Times(0)

		service := NewReservationService(reservationRepo, productRepo, idempotencymocks.NewMockProcessedKafkaMessageStore(gomock.NewController(t)), noopTxExecutor{})

		_, err := service.ReserveItems(context.Background(), 100, []dto.ReservationDTO{{ProductID: productID, Quantity: 3}})

		if !errors.Is(err, model.ErrInsufficientStock) {
			t.Errorf("err = %v, want %v", err, model.ErrInsufficientStock)
		}
	})

	t.Run("counts earlier lines of the same product against later ones", func(t *testing.T) {
		productID := uint64(1)
		productIDs := []uint64{productID, productID}
		product := testProduct(t, productID, 10, 0)
		productRepo := mocks.NewMockProductRepository(gomock.NewController(t))
		reservationRepo := mocks.NewMockReservationRepository(gomock.NewController(t))

		productRepo.EXPECT().GetByIDs(gomock.Any(), productIDs).Return([]*model.Product{product}, nil).Times(1)
		productRepo.EXPECT().Update(gomock.Any(), gomock.Any()).Times(0)
		reservationRepo.EXPECT().CreateBatch(gomock.Any(), gomock.Any()).Times(0)

		service := NewReservationService(reservationRepo, productRepo, idempotencymocks.NewMockProcessedKafkaMessageStore(gomock.NewController(t)), noopTxExecutor{})

		// 6 + 5 exceeds the 10 on hand, though each line alone would fit.
		_, err := service.ReserveItems(context.Background(), 100, []dto.ReservationDTO{
			{ProductID: productID, Quantity: 6},
			{ProductID: productID, Quantity: 5},
		})

		if !errors.Is(err, model.ErrInsufficientStock) {
			t.Errorf("err = %v, want %v", err, model.ErrInsufficientStock)
		}
	})

	t.Run("fails without creating the reservations when it loses a race for the product", func(t *testing.T) {
		productID := uint64(1)
		product := testProduct(t, productID, 10, 0)
		productRepo := mocks.NewMockProductRepository(gomock.NewController(t))
		reservationRepo := mocks.NewMockReservationRepository(gomock.NewController(t))

		productRepo.EXPECT().GetByIDs(gomock.Any(), []uint64{productID}).Return([]*model.Product{product}, nil).Times(1)
		productRepo.EXPECT().Update(gomock.Any(), gomock.Any()).Return(nil, repository.ErrConcurrentUpdate).Times(1)
		reservationRepo.EXPECT().CreateBatch(gomock.Any(), gomock.Any()).Times(0)

		service := NewReservationService(reservationRepo, productRepo, idempotencymocks.NewMockProcessedKafkaMessageStore(gomock.NewController(t)), noopTxExecutor{})

		_, err := service.ReserveItems(context.Background(), 100, []dto.ReservationDTO{{ProductID: productID, Quantity: 4}})

		if !errors.Is(err, repository.ErrConcurrentUpdate) {
			t.Errorf("err = %v, want %v", err, repository.ErrConcurrentUpdate)
		}
	})

	t.Run("propagates an error from fetching products", func(t *testing.T) {
		productID := uint64(1)
		wantErr := errors.New("db down")
		productRepo := mocks.NewMockProductRepository(gomock.NewController(t))
		reservationRepo := mocks.NewMockReservationRepository(gomock.NewController(t))

		productRepo.EXPECT().GetByIDs(gomock.Any(), []uint64{productID}).Return(nil, wantErr).Times(1)
		reservationRepo.EXPECT().CreateBatch(gomock.Any(), gomock.Any()).Times(0)

		service := NewReservationService(reservationRepo, productRepo, idempotencymocks.NewMockProcessedKafkaMessageStore(gomock.NewController(t)), noopTxExecutor{})

		_, err := service.ReserveItems(context.Background(), 100, []dto.ReservationDTO{{ProductID: productID, Quantity: 1}})

		if !errors.Is(err, wantErr) {
			t.Errorf("err = %v, want %v", err, wantErr)
		}
	})
}

func TestReservationServiceImpl_Cancel(t *testing.T) {
	msg := idempotencydto.KafkaMessageMetadata{MessageID: uuid.New(), CorrelationID: "corr-1", MessageType: "CancelReservation"}

	t.Run("releases every reservation held for the order from its product and deletes them", func(t *testing.T) {
		orderID := uint64(100)
		r1 := testReservation(t, 1, 10, orderID, 2, model.ReservationHolderBuyer)
		r2 := testReservation(t, 2, 11, orderID, 3, model.ReservationHolderBuyer)
		r3 := testReservation(t, 3, 10, orderID, 1, model.ReservationHolderBuyer)
		p10 := testProduct(t, 10, 10, 3)
		p11 := testProduct(t, 11, 5, 3)
		reservationRepo := mocks.NewMockReservationRepository(gomock.NewController(t))
		productRepo := mocks.NewMockProductRepository(gomock.NewController(t))
		processedMessageStore := idempotencymocks.NewMockProcessedKafkaMessageStore(gomock.NewController(t))
		processedMessageStore.EXPECT().MarkProcessed(gomock.Any(), msg).Return(false, nil).Times(1)

		reservationRepo.EXPECT().GetByOrderID(gomock.Any(), orderID).Return([]*model.StockReservation{r1, r2, r3}, nil).Times(1)
		productRepo.EXPECT().GetByIDs(gomock.Any(), []uint64{10, 11, 10}).Return([]*model.Product{p10, p11}, nil).Times(1)
		var gotUpdatedProducts []*model.Product
		productRepo.EXPECT().Update(gomock.Any(), gomock.Any()).Times(2).DoAndReturn(
			func(ctx context.Context, updatedProduct *model.Product) (*model.Product, error) {
				gotUpdatedProducts = append(gotUpdatedProducts, updatedProduct)
				return updatedProduct, nil
			},
		)
		reservationRepo.EXPECT().DeleteAll(gomock.Any(), []*model.StockReservation{r1, r2, r3}).Return(nil).Times(1)

		service := NewReservationService(reservationRepo, productRepo, processedMessageStore, noopTxExecutor{})

		if err := service.Cancel(context.Background(), msg, orderID); err != nil {
			t.Fatalf("unexpected error: %v", err)
		}
		for _, gotProduct := range gotUpdatedProducts {
			if gotProduct.Reserved() != 0 {
				t.Errorf("product %d Reserved() = %d, want 0", gotProduct.ID(), gotProduct.Reserved())
			}
		}
	})

	t.Run("skips reprocessing an already-processed message without error", func(t *testing.T) {
		orderID := uint64(100)
		reservationRepo := mocks.NewMockReservationRepository(gomock.NewController(t))
		productRepo := mocks.NewMockProductRepository(gomock.NewController(t))
		processedMessageStore := idempotencymocks.NewMockProcessedKafkaMessageStore(gomock.NewController(t))
		processedMessageStore.EXPECT().MarkProcessed(gomock.Any(), msg).Return(true, nil).Times(1)
		reservationRepo.EXPECT().GetByOrderID(gomock.Any(), gomock.Any()).Times(0)
		productRepo.EXPECT().Update(gomock.Any(), gomock.Any()).Times(0)
		reservationRepo.EXPECT().DeleteAll(gomock.Any(), gomock.Any()).Times(0)

		service := NewReservationService(reservationRepo, productRepo, processedMessageStore, noopTxExecutor{})

		if err := service.Cancel(context.Background(), msg, orderID); err != nil {
			t.Fatalf("unexpected error: %v", err)
		}
	})

	t.Run("propagates an error from fetching reservations", func(t *testing.T) {
		orderID := uint64(100)
		wantErr := errors.New("db down")
		reservationRepo := mocks.NewMockReservationRepository(gomock.NewController(t))
		productRepo := mocks.NewMockProductRepository(gomock.NewController(t))
		processedMessageStore := idempotencymocks.NewMockProcessedKafkaMessageStore(gomock.NewController(t))
		processedMessageStore.EXPECT().MarkProcessed(gomock.Any(), msg).Return(false, nil).Times(1)

		reservationRepo.EXPECT().GetByOrderID(gomock.Any(), orderID).Return(nil, wantErr).Times(1)
		productRepo.EXPECT().Update(gomock.Any(), gomock.Any()).Times(0)
		reservationRepo.EXPECT().DeleteAll(gomock.Any(), gomock.Any()).Times(0)

		service := NewReservationService(reservationRepo, productRepo, processedMessageStore, noopTxExecutor{})

		err := service.Cancel(context.Background(), msg, orderID)

		if !errors.Is(err, wantErr) {
			t.Errorf("err = %v, want %v", err, wantErr)
		}
	})
}

func TestReservationServiceImpl_HoldByCourier(t *testing.T) {
	msg := idempotencydto.KafkaMessageMetadata{MessageID: uuid.New(), CorrelationID: "corr-1", MessageType: "HoldReservation"}

	t.Run("moves every reservation for the order to the courier", func(t *testing.T) {
		orderID := uint64(100)
		r1 := testReservation(t, 1, 10, orderID, 2, model.ReservationHolderBuyer)
		r2 := testReservation(t, 2, 11, orderID, 3, model.ReservationHolderBuyer)
		reservationRepo := mocks.NewMockReservationRepository(gomock.NewController(t))
		processedMessageStore := idempotencymocks.NewMockProcessedKafkaMessageStore(gomock.NewController(t))
		processedMessageStore.EXPECT().MarkProcessed(gomock.Any(), msg).Return(false, nil).Times(1)

		reservationRepo.EXPECT().GetByOrderID(gomock.Any(), orderID).Return([]*model.StockReservation{r1, r2}, nil).Times(1)

		var gotUpdatedReservations []*model.StockReservation
		reservationRepo.EXPECT().Update(gomock.Any(), gomock.Any()).Times(2).DoAndReturn(
			func(ctx context.Context, reservation *model.StockReservation) (*model.StockReservation, error) {
				gotUpdatedReservations = append(gotUpdatedReservations, reservation)
				return reservation, nil
			},
		)

		service := NewReservationService(reservationRepo, mocks.NewMockProductRepository(gomock.NewController(t)), processedMessageStore, noopTxExecutor{})

		if err := service.HoldByCourier(context.Background(), msg, orderID); err != nil {
			t.Fatalf("unexpected error: %v", err)
		}

		if len(gotUpdatedReservations) != 2 {
			t.Fatalf("Update called %d times, want 2", len(gotUpdatedReservations))
		}
		for _, gotReservation := range gotUpdatedReservations {
			if gotReservation.Holder() != model.ReservationHolderCourier {
				t.Errorf("reservation Holder() = %v, want %v", gotReservation.Holder(), model.ReservationHolderCourier)
			}
		}
	})

	t.Run("skips reprocessing an already-processed message without error", func(t *testing.T) {
		orderID := uint64(100)
		reservationRepo := mocks.NewMockReservationRepository(gomock.NewController(t))
		processedMessageStore := idempotencymocks.NewMockProcessedKafkaMessageStore(gomock.NewController(t))
		processedMessageStore.EXPECT().MarkProcessed(gomock.Any(), msg).Return(true, nil).Times(1)
		reservationRepo.EXPECT().GetByOrderID(gomock.Any(), gomock.Any()).Times(0)
		reservationRepo.EXPECT().Update(gomock.Any(), gomock.Any()).Times(0)

		service := NewReservationService(reservationRepo, mocks.NewMockProductRepository(gomock.NewController(t)), processedMessageStore, noopTxExecutor{})

		if err := service.HoldByCourier(context.Background(), msg, orderID); err != nil {
			t.Fatalf("unexpected error: %v", err)
		}
	})

	t.Run("propagates an error from fetching reservations", func(t *testing.T) {
		orderID := uint64(100)
		wantErr := errors.New("db down")
		reservationRepo := mocks.NewMockReservationRepository(gomock.NewController(t))
		processedMessageStore := idempotencymocks.NewMockProcessedKafkaMessageStore(gomock.NewController(t))
		processedMessageStore.EXPECT().MarkProcessed(gomock.Any(), msg).Return(false, nil).Times(1)

		reservationRepo.EXPECT().GetByOrderID(gomock.Any(), orderID).Return(nil, wantErr).Times(1)
		reservationRepo.EXPECT().Update(gomock.Any(), gomock.Any()).Times(0)

		service := NewReservationService(reservationRepo, mocks.NewMockProductRepository(gomock.NewController(t)), processedMessageStore, noopTxExecutor{})

		err := service.HoldByCourier(context.Background(), msg, orderID)

		if !errors.Is(err, wantErr) {
			t.Errorf("err = %v, want %v", err, wantErr)
		}
	})

	t.Run("propagates an error updating a reservation's holder", func(t *testing.T) {
		orderID := uint64(100)
		wantErr := errors.New("db down")
		r1 := testReservation(t, 1, 10, orderID, 2, model.ReservationHolderBuyer)
		reservationRepo := mocks.NewMockReservationRepository(gomock.NewController(t))
		processedMessageStore := idempotencymocks.NewMockProcessedKafkaMessageStore(gomock.NewController(t))
		processedMessageStore.EXPECT().MarkProcessed(gomock.Any(), msg).Return(false, nil).Times(1)

		reservationRepo.EXPECT().GetByOrderID(gomock.Any(), orderID).Return([]*model.StockReservation{r1}, nil).Times(1)
		reservationRepo.EXPECT().Update(gomock.Any(), gomock.Any()).Return(nil, wantErr).Times(1)

		service := NewReservationService(reservationRepo, mocks.NewMockProductRepository(gomock.NewController(t)), processedMessageStore, noopTxExecutor{})

		err := service.HoldByCourier(context.Background(), msg, orderID)

		if !errors.Is(err, wantErr) {
			t.Errorf("err = %v, want %v", err, wantErr)
		}
	})
}

func TestReservationServiceImpl_Fulfill(t *testing.T) {
	msg := idempotencydto.KafkaMessageMetadata{MessageID: uuid.New(), CorrelationID: "corr-1", MessageType: "FulfillReservation"}

	t.Run("removes the reservations from on-hand and reserved stock and deletes them", func(t *testing.T) {
		orderID := uint64(100)
		productID := uint64(10)
		r1 := testReservation(t, 1, productID, orderID, 2, model.ReservationHolderCourier)
		product := testProduct(t, productID, 10, 2)
		reservationRepo := mocks.NewMockReservationRepository(gomock.NewController(t))
		productRepo := mocks.NewMockProductRepository(gomock.NewController(t))
		processedMessageStore := idempotencymocks.NewMockProcessedKafkaMessageStore(gomock.NewController(t))
		processedMessageStore.EXPECT().MarkProcessed(gomock.Any(), msg).Return(false, nil).Times(1)

		reservationRepo.EXPECT().GetByOrderID(gomock.Any(), orderID).Return([]*model.StockReservation{r1}, nil).Times(1)
		productRepo.EXPECT().GetByIDs(gomock.Any(), []uint64{productID}).Return([]*model.Product{product}, nil).Times(1)
		var gotUpdatedProducts []*model.Product
		productRepo.EXPECT().Update(gomock.Any(), gomock.Any()).Times(1).DoAndReturn(
			func(ctx context.Context, updatedProduct *model.Product) (*model.Product, error) {
				gotUpdatedProducts = append(gotUpdatedProducts, updatedProduct)
				return updatedProduct, nil
			},
		)
		reservationRepo.EXPECT().DeleteAll(gomock.Any(), []*model.StockReservation{r1}).Return(nil).Times(1)

		service := NewReservationService(reservationRepo, productRepo, processedMessageStore, noopTxExecutor{})

		if err := service.Fulfill(context.Background(), msg, orderID); err != nil {
			t.Fatalf("unexpected error: %v", err)
		}
		if gotUpdatedProducts[0].Quantity() != 8 || gotUpdatedProducts[0].Reserved() != 0 {
			t.Errorf("product passed to Update has quantity = %d, reserved = %d, want 8 and 0", gotUpdatedProducts[0].Quantity(), gotUpdatedProducts[0].Reserved())
		}
	})

	t.Run("skips reprocessing an already-processed message without error", func(t *testing.T) {
		orderID := uint64(100)
		reservationRepo := mocks.NewMockReservationRepository(gomock.NewController(t))
		productRepo := mocks.NewMockProductRepository(gomock.NewController(t))
		processedMessageStore := idempotencymocks.NewMockProcessedKafkaMessageStore(gomock.NewController(t))
		processedMessageStore.EXPECT().MarkProcessed(gomock.Any(), msg).Return(true, nil).Times(1)
		reservationRepo.EXPECT().GetByOrderID(gomock.Any(), gomock.Any()).Times(0)
		reservationRepo.EXPECT().DeleteAll(gomock.Any(), gomock.Any()).Times(0)
		productRepo.EXPECT().GetByIDs(gomock.Any(), gomock.Any()).Times(0)

		service := NewReservationService(reservationRepo, productRepo, processedMessageStore, noopTxExecutor{})

		if err := service.Fulfill(context.Background(), msg, orderID); err != nil {
			t.Fatalf("unexpected error: %v", err)
		}
	})

	t.Run("propagates an error from fetching reservations", func(t *testing.T) {
		orderID := uint64(100)
		wantErr := errors.New("db down")
		reservationRepo := mocks.NewMockReservationRepository(gomock.NewController(t))
		processedMessageStore := idempotencymocks.NewMockProcessedKafkaMessageStore(gomock.NewController(t))
		processedMessageStore.EXPECT().MarkProcessed(gomock.Any(), msg).Return(false, nil).Times(1)

		reservationRepo.EXPECT().GetByOrderID(gomock.Any(), orderID).Return(nil, wantErr).Times(1)
		reservationRepo.EXPECT().DeleteAll(gomock.Any(), gomock.Any()).Times(0)
		productRepo := mocks.NewMockProductRepository(gomock.NewController(t))
		productRepo.EXPECT().GetByIDs(gomock.Any(), gomock.Any()).Times(0)

		service := NewReservationService(reservationRepo, productRepo, processedMessageStore, noopTxExecutor{})

		err := service.Fulfill(context.Background(), msg, orderID)

		if !errors.Is(err, wantErr) {
			t.Errorf("err = %v, want %v", err, wantErr)
		}
	})

	t.Run("propagates an error fetching the products and does not delete the reservations", func(t *testing.T) {
		orderID := uint64(100)
		productID := uint64(10)
		wantErr := errors.New("db down")
		r1 := testReservation(t, 1, productID, orderID, 2, model.ReservationHolderCourier)
		reservationRepo := mocks.NewMockReservationRepository(gomock.NewController(t))
		productRepo := mocks.NewMockProductRepository(gomock.NewController(t))
		processedMessageStore := idempotencymocks.NewMockProcessedKafkaMessageStore(gomock.NewController(t))
		processedMessageStore.EXPECT().MarkProcessed(gomock.Any(), msg).Return(false, nil).Times(1)

		reservationRepo.EXPECT().GetByOrderID(gomock.Any(), orderID).Return([]*model.StockReservation{r1}, nil).Times(1)
		productRepo.EXPECT().GetByIDs(gomock.Any(), []uint64{productID}).Return(nil, wantErr).Times(1)
		productRepo.EXPECT().Update(gomock.Any(), gomock.Any()).Times(0)
		reservationRepo.EXPECT().DeleteAll(gomock.Any(), gomock.Any()).Times(0)

		service := NewReservationService(reservationRepo, productRepo, processedMessageStore, noopTxExecutor{})

		err := service.Fulfill(context.Background(), msg, orderID)

		if !errors.Is(err, wantErr) {
			t.Errorf("err = %v, want %v", err, wantErr)
		}
	})
}
