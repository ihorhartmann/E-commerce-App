package middleware

import (
	"errors"
	"io"
	"net/http"
	"net/http/httptest"
	"testing"

	"inventoryservice/interfaces/http/auth"
	"inventoryservice/interfaces/http/auth/mocks"

	"github.com/gofiber/fiber/v2"
	"github.com/google/uuid"
	"go.uber.org/mock/gomock"
)

const (
	healthPath       = "/health"
	productsPath     = "/api/v1/products"
	productPath      = "/api/v1/products/1"
	reservationsPath = "/api/v1/reservations"
	unlistedPath     = "/api/v1/unlisted"
)

// newAuthTestApp registers the middleware ahead of handlers on the service's real routes that answer 200 and report
// the principal they saw.
func newAuthTestApp(verifier auth.TokenVerifier) *fiber.App {
	app := fiber.New(fiber.Config{ErrorHandler: HandleError})
	app.Use(NewAuthMiddleware(verifier).Handle)
	handler := func(c *fiber.Ctx) error {
		principal, _ := c.Locals(principalLocalsKey).(auth.Principal)
		return c.Status(http.StatusOK).SendString(principal.UserID.String())
	}
	app.Get(healthPath, handler)
	app.Get("/api/v1/products/:id", handler)
	app.Post(productsPath, handler)
	app.Put("/api/v1/products/:id", handler)
	app.Post(reservationsPath, handler)
	app.Get(unlistedPath, handler)
	return app
}

func doRequest(t *testing.T, app *fiber.App, method, path, authorization string) *http.Response {
	t.Helper()
	req := httptest.NewRequest(method, path, nil)
	if authorization != "" {
		req.Header.Set(fiber.HeaderAuthorization, authorization)
	}
	resp, err := app.Test(req)
	if err != nil {
		t.Fatalf("request failed: %v", err)
	}
	return resp
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

		resp := doRequest(t, newAuthTestApp(verifier), http.MethodGet, productPath, "")

		if resp.StatusCode != http.StatusOK {
			t.Errorf("status = %d, want %d", resp.StatusCode, http.StatusOK)
		}
	})

	t.Run("lets the internal reservation call through without a token", func(t *testing.T) {
		ctrl := gomock.NewController(t)
		verifier := mocks.NewMockTokenVerifier(ctrl)
		verifier.EXPECT().Verify(gomock.Any(), gomock.Any()).Times(0)

		resp := doRequest(t, newAuthTestApp(verifier), http.MethodPost, reservationsPath, "")

		if resp.StatusCode != http.StatusOK {
			t.Errorf("status = %d, want %d", resp.StatusCode, http.StatusOK)
		}
	})

	t.Run("passes the request on with the principal when the token carries a required role", func(t *testing.T) {
		ctrl := gomock.NewController(t)
		verifier := mocks.NewMockTokenVerifier(ctrl)
		token := "admin-token"
		userID := uuid.New()
		verifier.EXPECT().Verify(gomock.Any(), token).
			Return(auth.Principal{UserID: userID, Roles: []string{"ADMIN"}}, nil).Times(1)

		resp := doRequest(t, newAuthTestApp(verifier), http.MethodPost, productsPath, "Bearer "+token)

		if resp.StatusCode != http.StatusOK {
			t.Fatalf("status = %d, want %d", resp.StatusCode, http.StatusOK)
		}
		if gotUserID := readBody(t, resp); gotUserID != userID.String() {
			t.Errorf("principal seen by the handler = %q, want %q", gotUserID, userID)
		}
	})

	t.Run("answers 401 with a Bearer challenge when no token is sent", func(t *testing.T) {
		ctrl := gomock.NewController(t)
		verifier := mocks.NewMockTokenVerifier(ctrl)
		verifier.EXPECT().Verify(gomock.Any(), gomock.Any()).Times(0)

		resp := doRequest(t, newAuthTestApp(verifier), http.MethodPost, productsPath, "")

		if resp.StatusCode != http.StatusUnauthorized {
			t.Errorf("status = %d, want %d", resp.StatusCode, http.StatusUnauthorized)
		}
		if got := resp.Header.Get(fiber.HeaderWWWAuthenticate); got != "Bearer" {
			t.Errorf("WWW-Authenticate = %q, want %q", got, "Bearer")
		}
	})

	t.Run("answers 401 when the Authorization header is not a Bearer token", func(t *testing.T) {
		ctrl := gomock.NewController(t)
		verifier := mocks.NewMockTokenVerifier(ctrl)
		verifier.EXPECT().Verify(gomock.Any(), gomock.Any()).Times(0)

		resp := doRequest(t, newAuthTestApp(verifier), http.MethodPost, productsPath, "Basic dXNlcjpwYXNz")

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

		resp := doRequest(t, newAuthTestApp(verifier), http.MethodPut, productPath, "Bearer "+token)

		if resp.StatusCode != http.StatusUnauthorized {
			t.Errorf("status = %d, want %d", resp.StatusCode, http.StatusUnauthorized)
		}
	})

	t.Run("answers 403 when the token lacks the required role", func(t *testing.T) {
		ctrl := gomock.NewController(t)
		verifier := mocks.NewMockTokenVerifier(ctrl)
		token := "client-token"
		verifier.EXPECT().Verify(gomock.Any(), token).
			Return(auth.Principal{UserID: uuid.New(), Roles: []string{"CLIENT"}}, nil).Times(1)

		resp := doRequest(t, newAuthTestApp(verifier), http.MethodPut, productPath, "Bearer "+token)

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
