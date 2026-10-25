package middleware

import (
	"errors"
	"io"
	"net/http"
	"net/http/httptest"
	"testing"

	storedto "inventoryservice/application/port/out/idempotency/dto"
	"inventoryservice/interfaces/http/httperror"
	"inventoryservice/interfaces/http/idempotency/dto"
	"inventoryservice/interfaces/http/idempotency/mocks"

	"github.com/gofiber/fiber/v2"
	"go.uber.org/mock/gomock"
)

func newIdempotencyTestApp(service *mocks.MockHTTPIdempotencyService, handler fiber.Handler) *fiber.App {
	return newErrorHandlingTestApp(handler, NewIdempotencyMiddleware(service).Handle)
}

func claimed() *dto.RequestAttempt {
	return &dto.RequestAttempt{Result: dto.Claimed}
}

func TestIdempotencyMiddleware_Handle(t *testing.T) {
	t.Run("lets a non-mutating request through without claiming a key", func(t *testing.T) {
		service := mocks.NewMockHTTPIdempotencyService(gomock.NewController(t))
		service.EXPECT().Claim(gomock.Any(), gomock.Any(), gomock.Any(), gomock.Any()).Times(0)
		app := fiber.New(fiber.Config{ErrorHandler: HandleMiddlewareError})
		app.Use(NewIdempotencyMiddleware(service).Handle)
		app.Get(testPath, func(c *fiber.Ctx) error { return c.SendStatus(http.StatusOK) })

		resp, err := app.Test(httptest.NewRequest(http.MethodGet, testPath, nil))

		if err != nil {
			t.Fatalf("request failed: %v", err)
		}
		if resp.StatusCode != http.StatusOK {
			t.Errorf("status = %d, want %d", resp.StatusCode, http.StatusOK)
		}
	})

	t.Run("rejects a mutating request without an Idempotency-Key", func(t *testing.T) {
		service := mocks.NewMockHTTPIdempotencyService(gomock.NewController(t))
		service.EXPECT().Claim(gomock.Any(), gomock.Any(), gomock.Any(), gomock.Any()).Times(0)
		app := newIdempotencyTestApp(service, func(c *fiber.Ctx) error { return c.SendStatus(http.StatusCreated) })

		resp := post(t, app, nil)

		if resp.StatusCode != http.StatusBadRequest {
			t.Errorf("status = %d, want %d", resp.StatusCode, http.StatusBadRequest)
		}
		if problem := decodeProblem(t, resp); problem.Code != httperror.ErrMissingIdempotencyKey.Code() {
			t.Errorf("code = %q, want %q", problem.Code, httperror.ErrMissingIdempotencyKey.Code())
		}
	})

	t.Run("records a successful response", func(t *testing.T) {
		key := "key-1"
		body := `{"id":1}`
		service := mocks.NewMockHTTPIdempotencyService(gomock.NewController(t))
		service.EXPECT().Claim(gomock.Any(), key, gomock.Any(), claimTTL).Return(claimed(), nil).Times(1)
		service.EXPECT().Complete(gomock.Any(), key, http.StatusCreated, []byte(body)).Return(nil).Times(1)
		service.EXPECT().Release(gomock.Any(), gomock.Any()).Times(0)
		app := newIdempotencyTestApp(service, func(c *fiber.Ctx) error {
			return c.Status(http.StatusCreated).SendString(body)
		})

		resp := post(t, app, map[string]string{idempotencyKeyHeader: key})

		if resp.StatusCode != http.StatusCreated {
			t.Errorf("status = %d, want %d", resp.StatusCode, http.StatusCreated)
		}
	})

	t.Run("records the response of a permanent failure, to replay it", func(t *testing.T) {
		key := "key-2"
		service := mocks.NewMockHTTPIdempotencyService(gomock.NewController(t))
		service.EXPECT().Claim(gomock.Any(), key, gomock.Any(), claimTTL).Return(claimed(), nil).Times(1)
		service.EXPECT().Complete(gomock.Any(), key, http.StatusConflict, gomock.Any()).Return(nil).Times(1)
		service.EXPECT().Release(gomock.Any(), gomock.Any()).Times(0)
		app := newIdempotencyTestApp(service, func(*fiber.Ctx) error {
			return errTestConflict.WithInternalMessage("rejected")
		})

		resp := post(t, app, map[string]string{idempotencyKeyHeader: key})

		if resp.StatusCode != http.StatusConflict {
			t.Errorf("status = %d, want %d", resp.StatusCode, http.StatusConflict)
		}
		if problem := decodeProblem(t, resp); problem.Code != errTestConflict.Code() {
			t.Errorf("code = %q, want %q", problem.Code, errTestConflict.Code())
		}
	})

	t.Run("releases the key after a transient failure, so a retry runs again", func(t *testing.T) {
		key := "key-3"
		service := mocks.NewMockHTTPIdempotencyService(gomock.NewController(t))
		service.EXPECT().Claim(gomock.Any(), key, gomock.Any(), claimTTL).Return(claimed(), nil).Times(1)
		service.EXPECT().Complete(gomock.Any(), gomock.Any(), gomock.Any(), gomock.Any()).Times(0)
		service.EXPECT().Release(gomock.Any(), key).Return(nil).Times(1)
		app := newIdempotencyTestApp(service, func(*fiber.Ctx) error {
			return errTestTransient.WithInternalMessage("changed concurrently")
		})

		resp := post(t, app, map[string]string{idempotencyKeyHeader: key})

		if resp.StatusCode != http.StatusServiceUnavailable {
			t.Errorf("status = %d, want %d", resp.StatusCode, http.StatusServiceUnavailable)
		}
	})

	t.Run("releases the key after an internal failure", func(t *testing.T) {
		key := "key-4"
		service := mocks.NewMockHTTPIdempotencyService(gomock.NewController(t))
		service.EXPECT().Claim(gomock.Any(), key, gomock.Any(), claimTTL).Return(claimed(), nil).Times(1)
		service.EXPECT().Complete(gomock.Any(), gomock.Any(), gomock.Any(), gomock.Any()).Times(0)
		service.EXPECT().Release(gomock.Any(), key).Return(nil).Times(1)
		app := newIdempotencyTestApp(service, func(*fiber.Ctx) error {
			return errors.New("db down")
		})

		resp := post(t, app, map[string]string{idempotencyKeyHeader: key})

		if resp.StatusCode != http.StatusInternalServerError {
			t.Errorf("status = %d, want %d", resp.StatusCode, http.StatusInternalServerError)
		}
	})

	t.Run("replays a cached response with its status and body", func(t *testing.T) {
		key := "key-5"
		body := `{"id":1}`
		attempt := &dto.RequestAttempt{Result: dto.Cached, Record: &storedto.HTTPRequestRecord{HTTPCode: http.StatusCreated, Response: []byte(body)}}
		service := mocks.NewMockHTTPIdempotencyService(gomock.NewController(t))
		service.EXPECT().Claim(gomock.Any(), key, gomock.Any(), claimTTL).Return(attempt, nil).Times(1)
		service.EXPECT().Complete(gomock.Any(), gomock.Any(), gomock.Any(), gomock.Any()).Times(0)
		gotHandlerCalls := 0
		app := newIdempotencyTestApp(service, func(c *fiber.Ctx) error {
			gotHandlerCalls++
			return c.SendStatus(http.StatusCreated)
		})

		resp := post(t, app, map[string]string{idempotencyKeyHeader: key})

		if resp.StatusCode != http.StatusCreated {
			t.Errorf("status = %d, want %d", resp.StatusCode, http.StatusCreated)
		}
		gotBody, err := io.ReadAll(resp.Body)
		if err != nil {
			t.Fatalf("failed to read the body: %v", err)
		}
		if string(gotBody) != body {
			t.Errorf("body = %q, want %q", gotBody, body)
		}
		if gotContentType := resp.Header.Get(fiber.HeaderContentType); gotContentType != fiber.MIMEApplicationJSON {
			t.Errorf("content type = %q, want %q", gotContentType, fiber.MIMEApplicationJSON)
		}
		if gotHandlerCalls != 0 {
			t.Errorf("handler calls = %d, want 0", gotHandlerCalls)
		}
	})

	t.Run("replays a cached error response as problem+json", func(t *testing.T) {
		key := "key-6"
		attempt := &dto.RequestAttempt{Result: dto.Cached, Record: &storedto.HTTPRequestRecord{HTTPCode: http.StatusConflict, Response: []byte(`{"code":"X"}`)}}
		service := mocks.NewMockHTTPIdempotencyService(gomock.NewController(t))
		service.EXPECT().Claim(gomock.Any(), key, gomock.Any(), claimTTL).Return(attempt, nil).Times(1)
		app := newIdempotencyTestApp(service, func(c *fiber.Ctx) error { return c.SendStatus(http.StatusCreated) })

		resp := post(t, app, map[string]string{idempotencyKeyHeader: key})

		if resp.StatusCode != http.StatusConflict {
			t.Errorf("status = %d, want %d", resp.StatusCode, http.StatusConflict)
		}
		if gotContentType := resp.Header.Get(fiber.HeaderContentType); gotContentType != "application/problem+json" {
			t.Errorf("content type = %q, want application/problem+json", gotContentType)
		}
	})

	t.Run("replays a cached empty response as empty", func(t *testing.T) {
		key := "key-7"
		attempt := &dto.RequestAttempt{Result: dto.Cached, Record: &storedto.HTTPRequestRecord{HTTPCode: http.StatusNoContent}}
		service := mocks.NewMockHTTPIdempotencyService(gomock.NewController(t))
		service.EXPECT().Claim(gomock.Any(), key, gomock.Any(), claimTTL).Return(attempt, nil).Times(1)
		app := newIdempotencyTestApp(service, func(c *fiber.Ctx) error { return c.SendStatus(http.StatusCreated) })

		resp := post(t, app, map[string]string{idempotencyKeyHeader: key})

		if resp.StatusCode != http.StatusNoContent {
			t.Errorf("status = %d, want %d", resp.StatusCode, http.StatusNoContent)
		}
		gotBody, err := io.ReadAll(resp.Body)
		if err != nil {
			t.Fatalf("failed to read the body: %v", err)
		}
		if len(gotBody) != 0 {
			t.Errorf("body = %q, want empty", gotBody)
		}
	})

	t.Run("rejects a key reused with a different request as 422", func(t *testing.T) {
		key := "key-8"
		attempt := &dto.RequestAttempt{Result: dto.HashMismatch}
		service := mocks.NewMockHTTPIdempotencyService(gomock.NewController(t))
		service.EXPECT().Claim(gomock.Any(), key, gomock.Any(), claimTTL).Return(attempt, nil).Times(1)
		app := newIdempotencyTestApp(service, func(c *fiber.Ctx) error { return c.SendStatus(http.StatusCreated) })

		resp := post(t, app, map[string]string{idempotencyKeyHeader: key})

		if resp.StatusCode != http.StatusUnprocessableEntity {
			t.Errorf("status = %d, want %d", resp.StatusCode, http.StatusUnprocessableEntity)
		}
		if problem := decodeProblem(t, resp); problem.Code != httperror.ErrIdempotencyKeyReused.Code() {
			t.Errorf("code = %q, want %q", problem.Code, httperror.ErrIdempotencyKeyReused.Code())
		}
	})

	t.Run("rejects a request still in progress as a retryable 409", func(t *testing.T) {
		key := "key-9"
		attempt := &dto.RequestAttempt{Result: dto.InProgress}
		service := mocks.NewMockHTTPIdempotencyService(gomock.NewController(t))
		service.EXPECT().Claim(gomock.Any(), key, gomock.Any(), claimTTL).Return(attempt, nil).Times(1)
		app := newIdempotencyTestApp(service, func(c *fiber.Ctx) error { return c.SendStatus(http.StatusCreated) })

		resp := post(t, app, map[string]string{idempotencyKeyHeader: key})

		if resp.StatusCode != http.StatusConflict {
			t.Errorf("status = %d, want %d", resp.StatusCode, http.StatusConflict)
		}
		if resp.Header.Get(fiber.HeaderRetryAfter) == "" {
			t.Error("expected a Retry-After header")
		}
		problem := decodeProblem(t, resp)
		if problem.Code != httperror.ErrRequestInProgress.Code() || !problem.Retryable {
			t.Errorf("got %+v, want a retryable %q", problem, httperror.ErrRequestInProgress.Code())
		}
	})

	t.Run("reports a failure to claim the key without running the handler", func(t *testing.T) {
		key := "key-10"
		service := mocks.NewMockHTTPIdempotencyService(gomock.NewController(t))
		service.EXPECT().Claim(gomock.Any(), key, gomock.Any(), claimTTL).Return(nil, errors.New("db down")).Times(1)
		service.EXPECT().Complete(gomock.Any(), gomock.Any(), gomock.Any(), gomock.Any()).Times(0)
		service.EXPECT().Release(gomock.Any(), gomock.Any()).Times(0)
		gotHandlerCalls := 0
		app := newIdempotencyTestApp(service, func(c *fiber.Ctx) error {
			gotHandlerCalls++
			return c.SendStatus(http.StatusCreated)
		})

		resp := post(t, app, map[string]string{idempotencyKeyHeader: key})

		if resp.StatusCode != http.StatusInternalServerError {
			t.Errorf("status = %d, want %d", resp.StatusCode, http.StatusInternalServerError)
		}
		if gotHandlerCalls != 0 {
			t.Errorf("handler calls = %d, want 0", gotHandlerCalls)
		}
	})
}
