package httperror_test

import (
	"encoding/json"
	"errors"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"testing"

	"inventoryservice/domain/apperror"
	"inventoryservice/interfaces/http/httperror"
	"inventoryservice/interfaces/http/httperror/dto"

	"github.com/gofiber/fiber/v2"
)

// Package-level, like real sentinels: errors.Is matches the occurrences of one sentinel, so every
// test must use the same sentinel.
var (
	errTestConflict    = apperror.New("TEST_PROBLEM_CONFLICT", apperror.CategoryConflict, "Test conflict")
	errTestNotFound    = apperror.New("TEST_PROBLEM_NOT_FOUND", apperror.CategoryNotFound, "Test not found")
	errTestTransient   = apperror.New("TEST_PROBLEM_TRANSIENT", apperror.CategoryTransient, "Test transient")
	errTestUnavailable = apperror.New("TEST_PROBLEM_UNAVAILABLE", apperror.CategoryUnavailable, "Test unavailable")
)

func TestWrite(t *testing.T) {
	t.Run("writes a coded error as a problem detail with its external message only", func(t *testing.T) {
		path := "/api/v1/reservations"
		externalMsg := "Not enough stock to reserve the requested items."
		err := errTestConflict.WithInternalMessage("cannot reserve").WithAttrs(slog.Int("available", 29)).WithExternalMessage(externalMsg)

		response, got := writeProblem(t, path, err)

		want := dto.ProblemDetail{
			Type:      "urn:ecommerce:error:INVENTORY.TEST_PROBLEM_CONFLICT",
			Title:     errTestConflict.Title(),
			Status:    http.StatusConflict,
			Detail:    externalMsg,
			Instance:  path,
			Code:      errTestConflict.Code(),
			Retryable: false,
		}
		if got != want {
			t.Fatalf("got %+v, want %+v", got, want)
		}
		if gotContentType := response.Header.Get(fiber.HeaderContentType); gotContentType != "application/problem+json" {
			t.Errorf("got content type %q, want application/problem+json", gotContentType)
		}
		if gotRetryAfter := response.Header.Get(fiber.HeaderRetryAfter); gotRetryAfter != "" {
			t.Errorf("got Retry-After %q, want none", gotRetryAfter)
		}
	})

	t.Run("writes a retryable error as 503 with Retry-After", func(t *testing.T) {
		response, got := writeProblem(t, "/api/v1/products/1", errTestUnavailable.WithInternalMessage("database down"))

		if got.Status != http.StatusServiceUnavailable || response.StatusCode != http.StatusServiceUnavailable {
			t.Errorf("got status %d (body %d), want %d", response.StatusCode, got.Status, http.StatusServiceUnavailable)
		}
		if !got.Retryable {
			t.Error("expected retryable to be true")
		}
		if gotRetryAfter := response.Header.Get(fiber.HeaderRetryAfter); gotRetryAfter != "1" {
			t.Errorf("got Retry-After %q, want 1", gotRetryAfter)
		}
	})

	t.Run("writes an error without a code as 500 without leaking its message", func(t *testing.T) {
		response, got := writeProblem(t, "/api/v1/products/1", errors.New("pq: password authentication failed"))

		if response.StatusCode != http.StatusInternalServerError {
			t.Errorf("got status %d, want %d", response.StatusCode, http.StatusInternalServerError)
		}
		if got.Code != apperror.ErrInternal.Code() || got.Title != apperror.ErrInternal.Title() || got.Detail != "" {
			t.Errorf("got %+v, want the generic internal error", got)
		}
	})
}

func TestWrite_FiberErrors(t *testing.T) {
	tests := []struct {
		name       string
		err        error
		wantStatus int
		wantCode   string
	}{
		{name: "gives a missing route its own code", err: fiber.ErrNotFound, wantStatus: http.StatusNotFound, wantCode: httperror.ErrRouteNotFound.Code()},
		{name: "gives a method the route doesn't allow its own code", err: fiber.ErrMethodNotAllowed, wantStatus: http.StatusMethodNotAllowed, wantCode: httperror.ErrMethodNotAllowed.Code()},
		{name: "gives a body over the limit its own code", err: fiber.ErrRequestEntityTooLarge, wantStatus: http.StatusRequestEntityTooLarge, wantCode: httperror.ErrRequestTooLarge.Code()},
		{name: "reports any other rejected request as invalid", err: fiber.ErrUnprocessableEntity, wantStatus: http.StatusBadRequest, wantCode: httperror.ErrInvalidRequest.Code()},
		{name: "reports a server-side Fiber error as internal", err: fiber.ErrServiceUnavailable, wantStatus: http.StatusInternalServerError, wantCode: apperror.ErrInternal.Code()},
	}
	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			response, got := writeProblem(t, "/api/v1/whatever", test.err)

			if response.StatusCode != test.wantStatus || got.Status != test.wantStatus {
				t.Errorf("got status %d (body %d), want %d", response.StatusCode, got.Status, test.wantStatus)
			}
			if got.Code != test.wantCode {
				t.Errorf("got code %q, want %q", got.Code, test.wantCode)
			}
		})
	}
}

func TestStatusOf(t *testing.T) {
	tests := []struct {
		name string
		err  error
		want int
	}{
		{name: "an invalid request is 400", err: httperror.ErrMalformedRequestBody, want: http.StatusBadRequest},
		{name: "a missing entity is 404", err: errTestNotFound, want: http.StatusNotFound},
		{name: "a business conflict is 409", err: errTestConflict, want: http.StatusConflict},
		{name: "a transient failure is 503", err: errTestTransient, want: http.StatusServiceUnavailable},
		{name: "an unavailable dependency is 503", err: errTestUnavailable, want: http.StatusServiceUnavailable},
		{name: "an internal failure is 500", err: apperror.ErrInternal, want: http.StatusInternalServerError},
		{name: "a missing token is 401", err: httperror.ErrMissingBearerToken, want: http.StatusUnauthorized},
		{name: "an invalid token is 401", err: httperror.ErrInvalidToken, want: http.StatusUnauthorized},
		{name: "a denied access is 403", err: httperror.ErrAccessDenied, want: http.StatusForbidden},
		{name: "a method not allowed is 405", err: httperror.ErrMethodNotAllowed, want: http.StatusMethodNotAllowed},
		{name: "a request too large is 413", err: httperror.ErrRequestTooLarge, want: http.StatusRequestEntityTooLarge},
		{name: "a reused idempotency key is 422", err: httperror.ErrIdempotencyKeyReused, want: http.StatusUnprocessableEntity},
		{name: "a request still in progress is 409", err: httperror.ErrRequestInProgress, want: http.StatusConflict},
	}
	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			if got := httperror.StatusOf(test.err); got != test.want {
				t.Fatalf("got %d, want %d", got, test.want)
			}
		})
	}
}

func writeProblem(t *testing.T, path string, err error) (*http.Response, dto.ProblemDetail) {
	t.Helper()
	app := fiber.New()
	app.Get(path, func(c *fiber.Ctx) error {
		return httperror.Write(c, err)
	})

	response, testErr := app.Test(httptest.NewRequest(http.MethodGet, path, nil))
	if testErr != nil {
		t.Fatalf("request failed: %v", testErr)
	}
	defer func() { _ = response.Body.Close() }()

	var body dto.ProblemDetail
	if decodeErr := json.NewDecoder(response.Body).Decode(&body); decodeErr != nil {
		t.Fatalf("decoding the body failed: %v", decodeErr)
	}

	return response, body
}
