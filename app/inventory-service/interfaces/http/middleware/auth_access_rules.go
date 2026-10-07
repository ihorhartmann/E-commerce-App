package middleware

import (
	"net/http"

	"inventoryservice/interfaces/http/auth"
)

const adminRole = "ADMIN"

// accessRules lists who may call each endpoint of this service; anything not listed is denied. Roles are checked here,
// before routing - whether a caller owns a particular resource is decided by the use cases.
func accessRules() []auth.AccessRule {
	return []auth.AccessRule{
		auth.PermitAll(http.MethodGet, "/health"),
		// the catalog is public: browsing products needs no login, ordering them does
		auth.PermitAll(http.MethodGet, "/api/v1/products/*"),
		auth.RequireRole(http.MethodPost, "/api/v1/products", adminRole),
		auth.RequireRole(http.MethodPut, "/api/v1/products/*", adminRole),
		// synchronous call from the order-service inside the network, not exposed by the gateway; how
		// service-to-service calls get authenticated is a separate step (stage 5 of the auth plan)
		auth.PermitAll(http.MethodPost, "/api/v1/reservations"),
	}
}
