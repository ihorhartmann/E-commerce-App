package middleware

import (
	"errors"
	"io"
	"net/http"
	"net/http/httptest"
	"testing"

	"shippingservice/adapter/in/http/auth"
	"shippingservice/adapter/in/http/auth/mocks"

	"github.com/gin-gonic/gin"
	"github.com/google/uuid"
	"go.uber.org/mock/gomock"
)

const (
	healthPath    = "/health"
	availablePath = "/api/v1/shipments/available"
	pickupPath    = "/api/v1/shipments/1/pickup"
	cancelPath    = "/api/v1/shipments/1/cancel"
	unlistedPath  = "/api/v1/unlisted"
)

// newAuthTestApp registers the middleware (behind the error handler, as in main) ahead of handlers on the service's
// real routes that answer 200 and report the principal they saw.
func newAuthTestApp(verifier auth.TokenVerifier) *gin.Engine {
	gin.SetMode(gin.TestMode)
	router := gin.New()
	router.Use(ErrorHandlerMiddleware(), NewAuthMiddleware(verifier).Handle)
	handler := func(c *gin.Context) {
		principal, _ := c.Value(auth.PrincipalContextKey).(auth.Principal)
		c.String(http.StatusOK, principal.UserID.String())
	}
	router.GET(healthPath, handler)
	router.GET(availablePath, handler)
	router.POST("/api/v1/shipments/:id/pickup", handler)
	router.POST("/api/v1/shipments/:id/cancel", handler)
	router.GET(unlistedPath, handler)
	return router
}

func doRequest(t *testing.T, router *gin.Engine, method, path, authorization string) *http.Response {
	t.Helper()
	req := httptest.NewRequest(method, path, nil)
	if authorization != "" {
		req.Header.Set(authorizationHeader, authorization)
	}
	rec := httptest.NewRecorder()
	router.ServeHTTP(rec, req)
	return rec.Result()
}

func readBody(t *testing.T, resp *http.Response) string {
	t.Helper()
	body, err := io.ReadAll(resp.Body)
	if err != nil {
		t.Fatalf("failed to read response body: %v", err)
	}
	return string(body)
}

func TestAuthMiddleware_Handle(t *testing.T) {
	t.Run("lets a public request through without a token", func(t *testing.T) {
		ctrl := gomock.NewController(t)
		verifier := mocks.NewMockTokenVerifier(ctrl)
		verifier.EXPECT().Verify(gomock.Any(), gomock.Any()).Times(0)

		resp := doRequest(t, newAuthTestApp(verifier), http.MethodGet, healthPath, "")

		if resp.StatusCode != http.StatusOK {
			t.Errorf("status = %d, want %d", resp.StatusCode, http.StatusOK)
		}
	})

	t.Run("passes the request on with the principal when the token carries a required role", func(t *testing.T) {
		ctrl := gomock.NewController(t)
		verifier := mocks.NewMockTokenVerifier(ctrl)
		token := "courier-token"
		userID := uuid.New()
		verifier.EXPECT().Verify(gomock.Any(), token).
			Return(auth.Principal{UserID: userID, Roles: []string{"COURIER"}}, nil).Times(1)

		resp := doRequest(t, newAuthTestApp(verifier), http.MethodPost, pickupPath, "Bearer "+token)

		if resp.StatusCode != http.StatusOK {
			t.Fatalf("status = %d, want %d", resp.StatusCode, http.StatusOK)
		}
		if gotUserID := readBody(t, resp); gotUserID != userID.String() {
			t.Errorf("principal seen by the handler = %q, want %q", gotUserID, userID)
		}
	})

	t.Run("lets a courier through to the available shipments", func(t *testing.T) {
		ctrl := gomock.NewController(t)
		verifier := mocks.NewMockTokenVerifier(ctrl)
		token := "courier-token"
		verifier.EXPECT().Verify(gomock.Any(), token).
			Return(auth.Principal{UserID: uuid.New(), Roles: []string{"COURIER"}}, nil).Times(1)

		resp := doRequest(t, newAuthTestApp(verifier), http.MethodGet, availablePath, "Bearer "+token)

		if resp.StatusCode != http.StatusOK {
			t.Errorf("status = %d, want %d", resp.StatusCode, http.StatusOK)
		}
	})

	t.Run("answers 403 when a customer asks for the shipments", func(t *testing.T) {
		ctrl := gomock.NewController(t)
		verifier := mocks.NewMockTokenVerifier(ctrl)
		token := "client-token"
		verifier.EXPECT().Verify(gomock.Any(), token).
			Return(auth.Principal{UserID: uuid.New(), Roles: []string{"CLIENT"}}, nil).Times(1)

		resp := doRequest(t, newAuthTestApp(verifier), http.MethodGet, availablePath, "Bearer "+token)

		if resp.StatusCode != http.StatusForbidden {
			t.Errorf("status = %d, want %d", resp.StatusCode, http.StatusForbidden)
		}
	})

	t.Run("answers 401 with a Bearer challenge when no token is sent", func(t *testing.T) {
		ctrl := gomock.NewController(t)
		verifier := mocks.NewMockTokenVerifier(ctrl)
		verifier.EXPECT().Verify(gomock.Any(), gomock.Any()).Times(0)

		resp := doRequest(t, newAuthTestApp(verifier), http.MethodPost, pickupPath, "")

		if resp.StatusCode != http.StatusUnauthorized {
			t.Errorf("status = %d, want %d", resp.StatusCode, http.StatusUnauthorized)
		}
		if got := resp.Header.Get(wwwAuthenticateHeader); got != "Bearer" {
			t.Errorf("WWW-Authenticate = %q, want %q", got, "Bearer")
		}
	})

	t.Run("answers 401 when the Authorization header is not a Bearer token", func(t *testing.T) {
		ctrl := gomock.NewController(t)
		verifier := mocks.NewMockTokenVerifier(ctrl)
		verifier.EXPECT().Verify(gomock.Any(), gomock.Any()).Times(0)

		resp := doRequest(t, newAuthTestApp(verifier), http.MethodPost, pickupPath, "Basic dXNlcjpwYXNz")

		if resp.StatusCode != http.StatusUnauthorized {
			t.Errorf("status = %d, want %d", resp.StatusCode, http.StatusUnauthorized)
		}
	})

	t.Run("answers 401 when the token is invalid", func(t *testing.T) {
		ctrl := gomock.NewController(t)
		verifier := mocks.NewMockTokenVerifier(ctrl)
		token := "expired-token"
		verifier.EXPECT().Verify(gomock.Any(), token).
			Return(auth.Principal{}, errors.Join(auth.ErrInvalidToken, errors.New("token is expired"))).Times(1)

		resp := doRequest(t, newAuthTestApp(verifier), http.MethodPost, pickupPath, "Bearer "+token)

		if resp.StatusCode != http.StatusUnauthorized {
			t.Errorf("status = %d, want %d", resp.StatusCode, http.StatusUnauthorized)
		}
	})

	t.Run("answers 403 when the token lacks the required role", func(t *testing.T) {
		ctrl := gomock.NewController(t)
		verifier := mocks.NewMockTokenVerifier(ctrl)
		token := "courier-token"
		verifier.EXPECT().Verify(gomock.Any(), token).
			Return(auth.Principal{UserID: uuid.New(), Roles: []string{"COURIER"}}, nil).Times(1)

		resp := doRequest(t, newAuthTestApp(verifier), http.MethodPost, cancelPath, "Bearer "+token)

		if resp.StatusCode != http.StatusForbidden {
			t.Errorf("status = %d, want %d", resp.StatusCode, http.StatusForbidden)
		}
	})

	t.Run("answers 401 for an unlisted endpoint called without a token", func(t *testing.T) {
		ctrl := gomock.NewController(t)
		verifier := mocks.NewMockTokenVerifier(ctrl)
		verifier.EXPECT().Verify(gomock.Any(), gomock.Any()).Times(0)

		resp := doRequest(t, newAuthTestApp(verifier), http.MethodGet, unlistedPath, "")

		if resp.StatusCode != http.StatusUnauthorized {
			t.Errorf("status = %d, want %d", resp.StatusCode, http.StatusUnauthorized)
		}
	})

	t.Run("answers 403 for an unlisted endpoint even with a valid token", func(t *testing.T) {
		ctrl := gomock.NewController(t)
		verifier := mocks.NewMockTokenVerifier(ctrl)
		token := "admin-token"
		verifier.EXPECT().Verify(gomock.Any(), token).
			Return(auth.Principal{UserID: uuid.New(), Roles: []string{"ADMIN"}}, nil).Times(1)

		resp := doRequest(t, newAuthTestApp(verifier), http.MethodGet, unlistedPath, "Bearer "+token)

		if resp.StatusCode != http.StatusForbidden {
			t.Errorf("status = %d, want %d", resp.StatusCode, http.StatusForbidden)
		}
	})
}
