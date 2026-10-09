package middleware

import (
	"net/http"

	"inventoryservice/interfaces/http/auth"
)

const (
	adminRole = "ADMIN"
	// held by the order-service's own client (client credentials), never by a user
	inventoryReserveRole = "INVENTORY_RESERVE"
)

// accessRules lists who may call each endpoint of this service; anything not listed is denied. Roles are checked here,
// before routing - whether a caller owns a particular resource is decided by the use cases.
func accessRules() []auth.AccessRule {
	return []auth.AccessRule{
		auth.PermitAll(http.MethodGet, "/health"),
		// the catalog is public: browsing products needs no login, ordering them does
		auth.PermitAll(http.MethodGet, "/api/v1/products"),
		auth.PermitAll(http.MethodGet, "/api/v1/products/*"),
		auth.RequireRole(http.MethodPost, "/api/v1/products", adminRole),
		auth.RequireRole(http.MethodPut, "/api/v1/products/*", adminRole),
		// synchronous call from the order-service with its own token, never a user's; not exposed by the gateway
		auth.RequireRole(http.MethodPost, "/api/v1/reservations", inventoryReserveRole),
	}
}
