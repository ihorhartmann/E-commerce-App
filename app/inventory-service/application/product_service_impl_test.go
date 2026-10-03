package application

import (
	"context"
	"errors"
	"reflect"
	"testing"

	"go.uber.org/mock/gomock"

	"inventoryservice/application/dto"
	idempotencydto "inventoryservice/application/port/out/idempotency/dto"
	idempotencymocks "inventoryservice/application/port/out/idempotency/mocks"
	"inventoryservice/application/port/out/repository/mocks"
	"inventoryservice/domain"
	"inventoryservice/domain/model"

	"github.com/google/uuid"
)

// noopTxExecutor runs the action directly, without an actual transaction - the application-layer
// unit tests only need Transaction() to invoke its argument, not to verify tx boundaries.
type noopTxExecutor struct{}

func (noopTxExecutor) Transaction(ctx context.Context, action func(context.Context) error) error {
	return action(ctx)
}

func TestProductServiceImpl_Create(t *testing.T) {
	newProduct := testNewProduct(t, 10)        // not yet persisted, id 0
	createdProduct := testProduct(t, 1, 10, 0) // what the repository returns once it assigns an id
	productRepo := mocks.NewMockProductRepository(gomock.NewController(t))
	productRepo.EXPECT().Create(gomock.Any(), newProduct).Return(createdProduct, nil).Times(1)

	service := NewProductService(productRepo, idempotencymocks.NewMockProcessedKafkaMessageStore(gomock.NewController(t)), noopTxExecutor{})

	got, err := service.Create(context.Background(), newProduct)

	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if !reflect.DeepEqual(got, createdProduct) {
		t.Errorf("Create() = %+v, want %+v", got, createdProduct)
	}
}

func TestProductServiceImpl_Update(t *testing.T) {
	t.Run("applies the new details to the stored product, keeping what it has reserved", func(t *testing.T) {
		id := uint64(1)
		name := "Gadget"
		quantity := uint(20)
		reserved := uint(4)
		stored := testProduct(t, id, 10, reserved)
		persisted := testProduct(t, id, quantity, reserved) // what the repository returns after storing it
		productRepo := mocks.NewMockProductRepository(gomock.NewController(t))
		productRepo.EXPECT().GetByID(gomock.Any(), id).Return(stored, nil).Times(1)

		var gotStored *model.Product
		productRepo.EXPECT().Update(gomock.Any(), gomock.Any()).Times(1).DoAndReturn(
			func(ctx context.Context, p *model.Product) (*model.Product, error) {
				gotStored = p
				return persisted, nil
			},
		)

		service := NewProductService(productRepo, idempotencymocks.NewMockProcessedKafkaMessageStore(gomock.NewController(t)), noopTxExecutor{})

		got, err := service.Update(context.Background(), dto.UpdateProductDTO{ID: id, Name: name, Description: "a gadget", Price: testMoney(t), Quantity: quantity})

		if err != nil {
			t.Fatalf("unexpected error: %v", err)
		}
		if got != persisted {
			t.Errorf("Update() = %+v, want %+v", got, persisted)
		}
		if gotStored.Name() != name || gotStored.Quantity() != quantity || gotStored.Reserved() != reserved {
			t.Errorf("stored %+v, want name %q, quantity %d and reserved %d", gotStored, name, quantity, reserved)
		}
	})
}

func TestProductServiceImpl_Get(t *testing.T) {
	t.Run("returns the product", func(t *testing.T) {
		id := uint64(1)
		want := testProduct(t, id, 10, 3)
		productRepo := mocks.NewMockProductRepository(gomock.NewController(t))
		productRepo.EXPECT().GetByID(gomock.Any(), id).Return(want, nil).Times(1)

		service := NewProductService(productRepo, idempotencymocks.NewMockProcessedKafkaMessageStore(gomock.NewController(t)), noopTxExecutor{})

		got, err := service.Get(context.Background(), id)

		if err != nil {
			t.Fatalf("unexpected error: %v", err)
		}
		if got != want {
			t.Errorf("Get() = %+v, want %+v", got, want)
		}
	})

	t.Run("rejects a zero id without calling the repository", func(t *testing.T) {
		productRepo := mocks.NewMockProductRepository(gomock.NewController(t))
		productRepo.EXPECT().GetByID(gomock.Any(), gomock.Any()).Times(0)

		service := NewProductService(productRepo, idempotencymocks.NewMockProcessedKafkaMessageStore(gomock.NewController(t)), noopTxExecutor{})

		_, err := service.Get(context.Background(), 0)

		if !errors.Is(err, domain.ErrInvalidInput) {
			t.Errorf("err = %v, want %v", err, domain.ErrInvalidInput)
		}
	})

	t.Run("propagates an error fetching the product", func(t *testing.T) {
		id := uint64(1)
		wantErr := errors.New("db down")
		productRepo := mocks.NewMockProductRepository(gomock.NewController(t))
		productRepo.EXPECT().GetByID(gomock.Any(), id).Return(nil, wantErr).Times(1)

		service := NewProductService(productRepo, idempotencymocks.NewMockProcessedKafkaMessageStore(gomock.NewController(t)), noopTxExecutor{})

		got, err := service.Get(context.Background(), id)

		if !errors.Is(err, wantErr) {
			t.Errorf("err = %v, want %v", err, wantErr)
		}
		if got != nil {
			t.Errorf("got = %+v, want nil", got)
		}
	})
}

func TestProductServiceImpl_Delete(t *testing.T) {
	t.Run("deletes the stored product", func(t *testing.T) {
		id := uint64(1)
		product := testProduct(t, id, 10, 0)
		productRepo := mocks.NewMockProductRepository(gomock.NewController(t))
		productRepo.EXPECT().GetByID(gomock.Any(), id).Return(product, nil).Times(1)
		productRepo.EXPECT().Delete(gomock.Any(), product).Return(nil).Times(1)

		service := NewProductService(productRepo, idempotencymocks.NewMockProcessedKafkaMessageStore(gomock.NewController(t)), noopTxExecutor{})

		if err := service.Delete(context.Background(), id); err != nil {
			t.Fatalf("unexpected error: %v", err)
		}
	})

	t.Run("rejects a zero id without calling the repository", func(t *testing.T) {
		productRepo := mocks.NewMockProductRepository(gomock.NewController(t))
		productRepo.EXPECT().GetByID(gomock.Any(), gomock.Any()).Times(0)
		productRepo.EXPECT().Delete(gomock.Any(), gomock.Any()).Times(0)

		service := NewProductService(productRepo, idempotencymocks.NewMockProcessedKafkaMessageStore(gomock.NewController(t)), noopTxExecutor{})

		err := service.Delete(context.Background(), 0)

		if !errors.Is(err, domain.ErrInvalidInput) {
			t.Errorf("err = %v, want %v", err, domain.ErrInvalidInput)
		}
	})
}

func TestProductServiceImpl_Restock(t *testing.T) {
	msg := idempotencydto.KafkaMessageMetadata{MessageID: uuid.New(), CorrelationID: "corr-1", MessageType: "Restock"}

	t.Run("adds the requested quantity back to each product", func(t *testing.T) {
		p1ID := uint64(1)
		p2ID := uint64(2)
		p1 := testProduct(t, p1ID, 5, 0)
		p2 := testProduct(t, p2ID, 8, 0)
		productRepo := mocks.NewMockProductRepository(gomock.NewController(t))
		processedMessageStore := idempotencymocks.NewMockProcessedKafkaMessageStore(gomock.NewController(t))
		processedMessageStore.EXPECT().MarkProcessed(gomock.Any(), msg).Return(false, nil).Times(1)

		productRepo.EXPECT().GetByID(gomock.Any(), p1ID).Return(p1, nil).Times(1)
		productRepo.EXPECT().GetByID(gomock.Any(), p2ID).Return(p2, nil).Times(1)

		var updated []*model.Product
		productRepo.EXPECT().Update(gomock.Any(), gomock.Any()).Times(2).DoAndReturn(
			func(ctx context.Context, p *model.Product) (*model.Product, error) {
				updated = append(updated, p)
				return p, nil
			},
		)

		service := NewProductService(productRepo, processedMessageStore, noopTxExecutor{})

		items := []dto.ReservationDTO{{ProductID: p1ID, Quantity: 3}, {ProductID: p2ID, Quantity: 2}}
		if err := service.Restock(context.Background(), msg, items); err != nil {
			t.Fatalf("unexpected error: %v", err)
		}

		if len(updated) != 2 {
			t.Fatalf("Update called %d times, want 2", len(updated))
		}
		if updated[0].Quantity() != 8 {
			t.Errorf("product 1 quantity = %d, want 8", updated[0].Quantity())
		}
		if updated[1].Quantity() != 10 {
			t.Errorf("product 2 quantity = %d, want 10", updated[1].Quantity())
		}
	})

	t.Run("skips reprocessing an already-processed message without error", func(t *testing.T) {
		productRepo := mocks.NewMockProductRepository(gomock.NewController(t))
		processedMessageStore := idempotencymocks.NewMockProcessedKafkaMessageStore(gomock.NewController(t))
		processedMessageStore.EXPECT().MarkProcessed(gomock.Any(), msg).Return(true, nil).Times(1)
		productRepo.EXPECT().GetByID(gomock.Any(), gomock.Any()).Times(0)
		productRepo.EXPECT().Update(gomock.Any(), gomock.Any()).Times(0)

		service := NewProductService(productRepo, processedMessageStore, noopTxExecutor{})

		items := []dto.ReservationDTO{{ProductID: 1, Quantity: 3}}
		if err := service.Restock(context.Background(), msg, items); err != nil {
			t.Fatalf("unexpected error: %v", err)
		}
	})

	t.Run("propagates an error fetching a product and stops", func(t *testing.T) {
		productID := uint64(1)
		wantErr := errors.New("db down")
		productRepo := mocks.NewMockProductRepository(gomock.NewController(t))
		processedMessageStore := idempotencymocks.NewMockProcessedKafkaMessageStore(gomock.NewController(t))
		processedMessageStore.EXPECT().MarkProcessed(gomock.Any(), msg).Return(false, nil).Times(1)
		productRepo.EXPECT().GetByID(gomock.Any(), productID).Return(nil, wantErr).Times(1)
		productRepo.EXPECT().Update(gomock.Any(), gomock.Any()).Times(0)

		service := NewProductService(productRepo, processedMessageStore, noopTxExecutor{})

		err := service.Restock(context.Background(), msg, []dto.ReservationDTO{{ProductID: productID, Quantity: 1}})

		if !errors.Is(err, wantErr) {
			t.Errorf("err = %v, want %v", err, wantErr)
		}
	})
}
