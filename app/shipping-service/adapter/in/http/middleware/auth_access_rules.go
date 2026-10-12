package middleware

import (
	"net/http"
	"shippingservice/adapter/in/http/auth"
)

const (
	courierRole = "COURIER"
)

// accessRules lists who may call each endpoint of this service; anything not listed is denied. Roles are checked here,
// before routing - whether the shipment is assigned to the calling courier is decided by the application layer.
func accessRules() []auth.AccessRule {
	return []auth.AccessRule{
		auth.PermitAll(http.MethodGet, "/health"),
		auth.RequireRole(http.MethodGet, "/api/v1/shipments/available", courierRole),
		auth.RequireRole(http.MethodGet, "/api/v1/shipments/mine", courierRole),
		auth.RequireRole(http.MethodGet, "/api/v1/shipments/*", courierRole),
		auth.RequireRole(http.MethodPost, "/api/v1/shipments/*/pickup", courierRole),
		auth.RequireRole(http.MethodPost, "/api/v1/shipments/*/deliver", courierRole),
		auth.RequireRole(http.MethodPost, "/api/v1/shipments/*/lose", courierRole),
		auth.RequireRole(http.MethodPost, "/api/v1/shipments/*/return", courierRole),
		auth.RequireRole(http.MethodPost, "/api/v1/shipments/*/cancel", courierRole),
	}
}
