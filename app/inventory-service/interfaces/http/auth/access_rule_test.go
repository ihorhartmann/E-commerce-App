package auth

import (
	"net/http"
	"testing"

	"github.com/google/uuid"
)

func TestAccessRule_Matches(t *testing.T) {
	t.Run("matches the exact method and path", func(t *testing.T) {
		path := "/api/v1/products"
		rule := RequireRole(http.MethodPost, path, "ADMIN")

		if !rule.Matches(http.MethodPost, path) {
			t.Error("expected the rule to match")
		}
	})

	t.Run("ignores a trailing slash on the request path", func(t *testing.T) {
		rule := RequireRole(http.MethodPost, "/api/v1/products", "ADMIN")

		if !rule.Matches(http.MethodPost, "/api/v1/products/") {
			t.Error("expected the rule to match")
		}
	})

	t.Run("a wildcard matches exactly one segment", func(t *testing.T) {
		rule := PermitAll(http.MethodGet, "/api/v1/products/*")

		if !rule.Matches(http.MethodGet, "/api/v1/products/42") {
			t.Error("expected the rule to match a single segment")
		}
		if rule.Matches(http.MethodGet, "/api/v1/products") {
			t.Error("expected the rule not to match a missing segment")
		}
		if rule.Matches(http.MethodGet, "/api/v1/products/42/stock") {
			t.Error("expected the rule not to match an extra segment")
		}
	})

	t.Run("does not match another method", func(t *testing.T) {
		path := "/api/v1/products/*"
		rule := PermitAll(http.MethodGet, path)

		if rule.Matches(http.MethodPut, "/api/v1/products/42") {
			t.Error("expected the rule not to match")
		}
	})

	t.Run("does not match another path of the same length", func(t *testing.T) {
		rule := PermitAll(http.MethodPost, "/api/v1/reservations")

		if rule.Matches(http.MethodPost, "/api/v1/products") {
			t.Error("expected the rule not to match")
		}
	})
}

func TestPrincipal_HasAnyRole(t *testing.T) {
	principal := Principal{UserID: uuid.New(), Roles: []string{"CLIENT", "offline_access"}}

	t.Run("true when the principal has one of the roles", func(t *testing.T) {
		if !principal.HasAnyRole("ADMIN", "CLIENT") {
			t.Error("expected the principal to have a matching role")
		}
	})

	t.Run("false when the principal has none of the roles", func(t *testing.T) {
		if principal.HasAnyRole("ADMIN", "COURIER") {
			t.Error("expected the principal to have no matching role")
		}
	})

	t.Run("false when no roles are required", func(t *testing.T) {
		if principal.HasAnyRole() {
			t.Error("expected no match for an empty role list")
		}
	})
}
