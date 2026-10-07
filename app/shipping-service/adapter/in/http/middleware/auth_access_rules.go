package middleware

import (
	"net/http"
	"shippingservice/adapter/in/http/auth"
)

const (
	clientRole  = "CLIENT"
	courierRole = "COURIER"
	adminRole   = "ADMIN"
)

// accessRules lists who may call each endpoint of this service; anything not listed is denied. Roles are checked here,
// before routing - whether the shipment belongs to the caller (the customer who ordered it, the courier assigned to
// it) is decided by the application layer.
func accessRules() []auth.AccessRule {
	return []auth.AccessRule{
		auth.PermitAll(http.MethodGet, "/health"),
		auth.RequireRole(http.MethodGet, "/api/v1/shipments/tracking/*", clientRole, courierRole),
		auth.RequireRole(http.MethodPost, "/api/v1/shipments/*/pickup", courierRole),
		auth.RequireRole(http.MethodPost, "/api/v1/shipments/*/deliver", courierRole),
		auth.RequireRole(http.MethodPost, "/api/v1/shipments/*/lose", courierRole),
		auth.RequireRole(http.MethodPost, "/api/v1/shipments/*/return", courierRole),
		// a customer will cancel the order through the order-service instead (docs/business_tasks.md);
		// admin-only until then
		auth.RequireRole(http.MethodPost, "/api/v1/shipments/*/cancel", adminRole),
	}
}
