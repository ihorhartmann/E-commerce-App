package middleware

import (
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"net/http/httptest"
	"testing"

	"shippingservice/adapter/in/http/httperror"
	"shippingservice/adapter/in/http/httperror/dto"
	"shippingservice/domain/apperror"

	"github.com/gin-gonic/gin"
)

// Package-level, like real sentinels: errors.Is matches the occurrences of one sentinel, so every
// test must use the same sentinel.
var (
	errTestConflict  = apperror.New("TEST_MIDDLEWARE_CONFLICT", apperror.CategoryConflict, "Test conflict")
	errTestTransient = apperror.New("TEST_MIDDLEWARE_TRANSIENT", apperror.CategoryTransient, "Test transient")
)

const testPath = "/things"

// newErrorHandlingTestApp wires the two error handlers the way main does, around one POST route.
func newErrorHandlingTestApp(handler gin.HandlerFunc, middlewares ...gin.HandlerFunc) *gin.Engine {
	gin.SetMode(gin.TestMode)
	router := gin.New()
	router.Use(HandleMiddlewareError, Recover)
	router.Use(middlewares...)
	router.Use(HandleControllerError)
	router.POST(testPath, handler)
	return router
}

func post(t *testing.T, router *gin.Engine, headers map[string]string) *http.Response {
	t.Helper()
	req := httptest.NewRequest(http.MethodPost, testPath, nil)
	for name, value := range headers {
		req.Header.Set(name, value)
	}
	rec := httptest.NewRecorder()
	router.ServeHTTP(rec, req)
	return rec.Result()
}

func decodeProblem(t *testing.T, resp *http.Response) dto.ProblemDetail {
	t.Helper()
	defer func() { _ = resp.Body.Close() }()
	body, err := io.ReadAll(resp.Body)
	if err != nil {
		t.Fatalf("failed to read response body: %v", err)
	}
	var problem dto.ProblemDetail
	if err = json.Unmarshal(body, &problem); err != nil {
		t.Fatalf("failed to decode %q as a problem detail: %v", body, err)
	}
	return problem
}

func TestHandleControllerError(t *testing.T) {
	t.Run("writes a controller's error as a problem detail and records it for HandledError", func(t *testing.T) {
		var gotHandledError error
		recordHandledError := func(c *gin.Context) {
			c.Next()
			gotHandledError = HandledError(c)
		}
		router := newErrorHandlingTestApp(func(c *gin.Context) {
			_ = c.Error(errTestConflict.WithInternalMessage("rejected"))
		}, recordHandledError)

		resp := post(t, router, nil)

		if resp.StatusCode != http.StatusConflict {
			t.Errorf("status = %d, want %d", resp.StatusCode, http.StatusConflict)
		}
		if problem := decodeProblem(t, resp); problem.Code != errTestConflict.Code() {
			t.Errorf("code = %q, want %q", problem.Code, errTestConflict.Code())
		}
		if !errors.Is(gotHandledError, errTestConflict) {
			t.Errorf("HandledError = %v, want %v", gotHandledError, errTestConflict)
		}
	})

	t.Run("writes a panic in a controller as an internal error", func(t *testing.T) {
		router := newErrorHandlingTestApp(func(*gin.Context) {
			panic("boom")
		})

		resp := post(t, router, nil)

		if resp.StatusCode != http.StatusInternalServerError {
			t.Errorf("status = %d, want %d", resp.StatusCode, http.StatusInternalServerError)
		}
		if problem := decodeProblem(t, resp); problem.Code != apperror.ErrInternal.Code() {
			t.Errorf("code = %q, want %q", problem.Code, apperror.ErrInternal.Code())
		}
	})

	t.Run("leaves a successful response as it is, with no handled error", func(t *testing.T) {
		var gotHandledError error
		recordHandledError := func(c *gin.Context) {
			c.Next()
			gotHandledError = HandledError(c)
		}
		router := newErrorHandlingTestApp(func(c *gin.Context) {
			c.Status(http.StatusCreated)
		}, recordHandledError)

		resp := post(t, router, nil)

		if resp.StatusCode != http.StatusCreated {
			t.Errorf("status = %d, want %d", resp.StatusCode, http.StatusCreated)
		}
		if gotHandledError != nil {
			t.Errorf("HandledError = %v, want nil", gotHandledError)
		}
	})
}

func TestHandleMiddlewareError(t *testing.T) {
	t.Run("writes the error of a middleware above the controller as a problem detail", func(t *testing.T) {
		rejecting := func(c *gin.Context) {
			_ = c.Error(httperror.ErrMissingIdempotencyKey)
			c.Abort()
		}
		router := newErrorHandlingTestApp(func(c *gin.Context) {
			c.Status(http.StatusCreated)
		}, rejecting)

		resp := post(t, router, nil)

		if resp.StatusCode != http.StatusBadRequest {
			t.Errorf("status = %d, want %d", resp.StatusCode, http.StatusBadRequest)
		}
		if problem := decodeProblem(t, resp); problem.Code != httperror.ErrMissingIdempotencyKey.Code() {
			t.Errorf("code = %q, want %q", problem.Code, httperror.ErrMissingIdempotencyKey.Code())
		}
	})
}

func TestRecover(t *testing.T) {
	t.Run("turns a panic in a middleware into an internal error response", func(t *testing.T) {
		panicking := func(*gin.Context) {
			panic("boom")
		}
		router := newErrorHandlingTestApp(func(c *gin.Context) {
			c.Status(http.StatusCreated)
		}, panicking)

		resp := post(t, router, nil)

		if resp.StatusCode != http.StatusInternalServerError {
			t.Errorf("status = %d, want %d", resp.StatusCode, http.StatusInternalServerError)
		}
		if problem := decodeProblem(t, resp); problem.Code != apperror.ErrInternal.Code() {
			t.Errorf("code = %q, want %q", problem.Code, apperror.ErrInternal.Code())
		}
	})
}
