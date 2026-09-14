package idempotency

import (
	"context"
	"errors"
	"reflect"
	"testing"
	"time"

	"go.uber.org/mock/gomock"

	"shippingservice/adapter/in/http/idempotency/dto"
	storedto "shippingservice/application/port/out/idempotency/dto"
	"shippingservice/application/port/out/idempotency/mocks"
)

const testTTL = 60 * time.Second

var testFingerprint = dto.RequestFingerprint{Method: "POST", Path: "/api/v1/shipments", Body: []byte(`{"orderId":1}`)}

// testHash computes the same hash Claim would compute internally for fingerprint, so tests can set
// up store expectations/records without duplicating the hashing algorithm.
func testHash(fingerprint dto.RequestFingerprint) string {
	return (&httpIdempotencyService{}).hashRequest(fingerprint)
}

func TestHTTPIdempotencyService_hashRequest(t *testing.T) {
	t.Run("computes the same hash for the same method, path and body", func(t *testing.T) {
		service := &httpIdempotencyService{}

		first := service.hashRequest(testFingerprint)
		second := service.hashRequest(testFingerprint)

		if first != second {
			t.Errorf("hashRequest() = %v, then %v, want equal hashes for the same fingerprint", first, second)
		}
	})

	t.Run("computes a different hash when the body differs", func(t *testing.T) {
		service := &httpIdempotencyService{}
		other := dto.RequestFingerprint{Method: testFingerprint.Method, Path: testFingerprint.Path, Body: []byte(`{"orderId":2}`)}

		got := service.hashRequest(testFingerprint)
		want := service.hashRequest(other)

		if got == want {
			t.Errorf("hashRequest() = %v, want a different hash for a different body", got)
		}
	})
}

func TestHTTPIdempotencyService_Claim(t *testing.T) {
	t.Run("inserts a new row and proceeds", func(t *testing.T) {
		idempotencyKey := "key-1"
		requestHash := testHash(testFingerprint)
		store := mocks.NewMockHTTPRequestStore(gomock.NewController(t))
		store.EXPECT().Insert(gomock.Any(), idempotencyKey, requestHash).Return(true, nil).Times(1)
		store.EXPECT().Find(gomock.Any(), gomock.Any()).Times(0)
		store.EXPECT().Reclaim(gomock.Any(), gomock.Any(), gomock.Any()).Times(0)
		service := NewHTTPIdempotencyService(store)

		got, err := service.Claim(context.Background(), idempotencyKey, testFingerprint, testTTL)

		if err != nil {
			t.Fatalf("unexpected error: %v", err)
		}
		want := &dto.RequestAttempt{Result: dto.Claimed}
		if !reflect.DeepEqual(got, want) {
			t.Errorf("Claim() = %+v, want %+v", got, want)
		}
	})

	t.Run("returns hash mismatch when the existing record's hash differs", func(t *testing.T) {
		idempotencyKey := "key-1"
		requestHash := testHash(testFingerprint)
		record := &storedto.HTTPRequestRecord{ID: idempotencyKey, RequestHash: "different-hash", ClaimedAt: time.Now()}
		store := mocks.NewMockHTTPRequestStore(gomock.NewController(t))
		store.EXPECT().Insert(gomock.Any(), idempotencyKey, requestHash).Return(false, nil).Times(1)
		store.EXPECT().Find(gomock.Any(), idempotencyKey).Return(record, nil).Times(1)
		store.EXPECT().Reclaim(gomock.Any(), gomock.Any(), gomock.Any()).Times(0)
		service := NewHTTPIdempotencyService(store)

		got, err := service.Claim(context.Background(), idempotencyKey, testFingerprint, testTTL)

		if err != nil {
			t.Fatalf("unexpected error: %v", err)
		}
		want := &dto.RequestAttempt{Result: dto.HashMismatch, Record: record}
		if !reflect.DeepEqual(got, want) {
			t.Errorf("Claim() = %+v, want %+v", got, want)
		}
	})

	t.Run("returns replay when the existing record already completed with a matching hash", func(t *testing.T) {
		idempotencyKey := "key-1"
		requestHash := testHash(testFingerprint)
		completedAt := time.Now().Add(-time.Minute)
		record := &storedto.HTTPRequestRecord{
			ID: idempotencyKey, RequestHash: requestHash, HTTPCode: 200,
			ClaimedAt: time.Now().Add(-2 * time.Minute), CompletedAt: &completedAt,
		}
		store := mocks.NewMockHTTPRequestStore(gomock.NewController(t))
		store.EXPECT().Insert(gomock.Any(), idempotencyKey, requestHash).Return(false, nil).Times(1)
		store.EXPECT().Find(gomock.Any(), idempotencyKey).Return(record, nil).Times(1)
		store.EXPECT().Reclaim(gomock.Any(), gomock.Any(), gomock.Any()).Times(0)
		service := NewHTTPIdempotencyService(store)

		got, err := service.Claim(context.Background(), idempotencyKey, testFingerprint, testTTL)

		if err != nil {
			t.Fatalf("unexpected error: %v", err)
		}
		want := &dto.RequestAttempt{Result: dto.Cached, Record: record}
		if !reflect.DeepEqual(got, want) {
			t.Errorf("Claim() = %+v, want %+v", got, want)
		}
	})

	t.Run("returns in progress when the existing record is fresh and not yet completed", func(t *testing.T) {
		idempotencyKey := "key-1"
		requestHash := testHash(testFingerprint)
		record := &storedto.HTTPRequestRecord{ID: idempotencyKey, RequestHash: requestHash, ClaimedAt: time.Now()}
		store := mocks.NewMockHTTPRequestStore(gomock.NewController(t))
		store.EXPECT().Insert(gomock.Any(), idempotencyKey, requestHash).Return(false, nil).Times(1)
		store.EXPECT().Find(gomock.Any(), idempotencyKey).Return(record, nil).Times(1)
		store.EXPECT().Reclaim(gomock.Any(), gomock.Any(), gomock.Any()).Times(0)
		service := NewHTTPIdempotencyService(store)

		got, err := service.Claim(context.Background(), idempotencyKey, testFingerprint, testTTL)

		if err != nil {
			t.Fatalf("unexpected error: %v", err)
		}
		want := &dto.RequestAttempt{Result: dto.InProgress, Record: record}
		if !reflect.DeepEqual(got, want) {
			t.Errorf("Claim() = %+v, want %+v", got, want)
		}
	})

	t.Run("reclaims a stale in-progress record and proceeds", func(t *testing.T) {
		idempotencyKey := "key-1"
		requestHash := testHash(testFingerprint)
		record := &storedto.HTTPRequestRecord{ID: idempotencyKey, RequestHash: requestHash, ClaimedAt: time.Now().Add(-2 * testTTL)}
		store := mocks.NewMockHTTPRequestStore(gomock.NewController(t))
		store.EXPECT().Insert(gomock.Any(), idempotencyKey, requestHash).Return(false, nil).Times(1)
		store.EXPECT().Find(gomock.Any(), idempotencyKey).Return(record, nil).Times(1)
		store.EXPECT().Reclaim(gomock.Any(), idempotencyKey, testTTL).Return(true, nil).Times(1)
		service := NewHTTPIdempotencyService(store)

		got, err := service.Claim(context.Background(), idempotencyKey, testFingerprint, testTTL)

		if err != nil {
			t.Fatalf("unexpected error: %v", err)
		}
		want := &dto.RequestAttempt{Result: dto.Claimed}
		if !reflect.DeepEqual(got, want) {
			t.Errorf("Claim() = %+v, want %+v", got, want)
		}
	})

	t.Run("returns in progress when reclaiming a stale record loses a race", func(t *testing.T) {
		idempotencyKey := "key-1"
		requestHash := testHash(testFingerprint)
		record := &storedto.HTTPRequestRecord{ID: idempotencyKey, RequestHash: requestHash, ClaimedAt: time.Now().Add(-2 * testTTL)}
		store := mocks.NewMockHTTPRequestStore(gomock.NewController(t))
		store.EXPECT().Insert(gomock.Any(), idempotencyKey, requestHash).Return(false, nil).Times(1)
		store.EXPECT().Find(gomock.Any(), idempotencyKey).Return(record, nil).Times(1)
		store.EXPECT().Reclaim(gomock.Any(), idempotencyKey, testTTL).Return(false, nil).Times(1)
		service := NewHTTPIdempotencyService(store)

		got, err := service.Claim(context.Background(), idempotencyKey, testFingerprint, testTTL)

		if err != nil {
			t.Fatalf("unexpected error: %v", err)
		}
		want := &dto.RequestAttempt{Result: dto.InProgress, Record: record}
		if !reflect.DeepEqual(got, want) {
			t.Errorf("Claim() = %+v, want %+v", got, want)
		}
	})

	t.Run("propagates an error from Insert", func(t *testing.T) {
		idempotencyKey := "key-1"
		requestHash := testHash(testFingerprint)
		wantErr := errors.New("db down")
		store := mocks.NewMockHTTPRequestStore(gomock.NewController(t))
		store.EXPECT().Insert(gomock.Any(), idempotencyKey, requestHash).Return(false, wantErr).Times(1)
		store.EXPECT().Find(gomock.Any(), gomock.Any()).Times(0)
		store.EXPECT().Reclaim(gomock.Any(), gomock.Any(), gomock.Any()).Times(0)
		service := NewHTTPIdempotencyService(store)

		got, err := service.Claim(context.Background(), idempotencyKey, testFingerprint, testTTL)

		if !errors.Is(err, wantErr) {
			t.Fatalf("Claim() error = %v, want %v", err, wantErr)
		}
		if got != nil {
			t.Errorf("Claim() = %+v, want nil", got)
		}
	})

	t.Run("propagates an error from Get", func(t *testing.T) {
		idempotencyKey := "key-1"
		requestHash := testHash(testFingerprint)
		wantErr := errors.New("db down")
		store := mocks.NewMockHTTPRequestStore(gomock.NewController(t))
		store.EXPECT().Insert(gomock.Any(), idempotencyKey, requestHash).Return(false, nil).Times(1)
		store.EXPECT().Find(gomock.Any(), idempotencyKey).Return(nil, wantErr).Times(1)
		store.EXPECT().Reclaim(gomock.Any(), gomock.Any(), gomock.Any()).Times(0)
		service := NewHTTPIdempotencyService(store)

		got, err := service.Claim(context.Background(), idempotencyKey, testFingerprint, testTTL)

		if !errors.Is(err, wantErr) {
			t.Fatalf("Claim() error = %v, want %v", err, wantErr)
		}
		if got != nil {
			t.Errorf("Claim() = %+v, want nil", got)
		}
	})

	t.Run("propagates an error from Reclaim", func(t *testing.T) {
		idempotencyKey := "key-1"
		requestHash := testHash(testFingerprint)
		wantErr := errors.New("db down")
		record := &storedto.HTTPRequestRecord{ID: idempotencyKey, RequestHash: requestHash, ClaimedAt: time.Now().Add(-2 * testTTL)}
		store := mocks.NewMockHTTPRequestStore(gomock.NewController(t))
		store.EXPECT().Insert(gomock.Any(), idempotencyKey, requestHash).Return(false, nil).Times(1)
		store.EXPECT().Find(gomock.Any(), idempotencyKey).Return(record, nil).Times(1)
		store.EXPECT().Reclaim(gomock.Any(), idempotencyKey, testTTL).Return(false, wantErr).Times(1)
		service := NewHTTPIdempotencyService(store)

		got, err := service.Claim(context.Background(), idempotencyKey, testFingerprint, testTTL)

		if !errors.Is(err, wantErr) {
			t.Fatalf("Claim() error = %v, want %v", err, wantErr)
		}
		if got != nil {
			t.Errorf("Claim() = %+v, want nil", got)
		}
	})
}

func TestHTTPIdempotencyService_Complete(t *testing.T) {
	t.Run("delegates to the store", func(t *testing.T) {
		idempotencyKey := "key-1"
		httpCode := 200
		response := []byte(`{"ok":true}`)
		store := mocks.NewMockHTTPRequestStore(gomock.NewController(t))
		store.EXPECT().Complete(gomock.Any(), idempotencyKey, httpCode, response).Return(nil).Times(1)
		service := NewHTTPIdempotencyService(store)

		err := service.Complete(context.Background(), idempotencyKey, httpCode, response)

		if err != nil {
			t.Fatalf("unexpected error: %v", err)
		}
	})

	t.Run("propagates an error from the store", func(t *testing.T) {
		idempotencyKey := "key-1"
		httpCode := 500
		wantErr := errors.New("db down")
		store := mocks.NewMockHTTPRequestStore(gomock.NewController(t))
		store.EXPECT().Complete(gomock.Any(), idempotencyKey, httpCode, []byte(nil)).Return(wantErr).Times(1)
		service := NewHTTPIdempotencyService(store)

		err := service.Complete(context.Background(), idempotencyKey, httpCode, nil)

		if !errors.Is(err, wantErr) {
			t.Fatalf("Complete() error = %v, want %v", err, wantErr)
		}
	})
}
