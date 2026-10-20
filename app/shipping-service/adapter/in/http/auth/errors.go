package auth

import "shippingservice/domain/apperror"

// ErrMissingPrincipal means a handler that needs the caller ran without AuthMiddleware authenticating
// one - a bug in the access rules or the middleware order.
var ErrMissingPrincipal = apperror.New("MISSING_PRINCIPAL", apperror.CategoryInternal, "Internal server error")
