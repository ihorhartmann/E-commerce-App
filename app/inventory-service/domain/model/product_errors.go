package model

import "inventoryservice/domain/apperror"

var (
	ErrInvalidProductName       = apperror.New("INVALID_PRODUCT_NAME", apperror.CategoryInvalid, "Product name must not be blank")
	ErrInvalidProductPrice      = apperror.New("INVALID_PRODUCT_PRICE", apperror.CategoryInvalid, "Product price must be positive")
	ErrUnsupportedPriceCurrency = apperror.New("UNSUPPORTED_PRICE_CURRENCY", apperror.CategoryInvalid, "Product price must be in EUR")
	ErrInsufficientStock        = apperror.New("INSUFFICIENT_STOCK", apperror.CategoryConflict, "Insufficient stock")
	ErrQuantityBelowReserved    = apperror.New("QUANTITY_BELOW_RESERVED", apperror.CategoryConflict, "Quantity can't be lower than the reserved quantity")
	ErrProductReserved          = apperror.New("PRODUCT_RESERVED", apperror.CategoryConflict, "Product has reserved stock")
	// ErrInconsistentProduct means a stored product breaks the product's own rules - corrupted data,
	// not a request to reject.
	ErrInconsistentProduct = apperror.New("INCONSISTENT_PRODUCT", apperror.CategoryInternal, "Internal server error")
	// ErrProductInvariantViolated means this service's own code asked the product for something no caller
	// should - a bug, not corrupted data and not a request to reject. The internal message says which rule.
	ErrProductInvariantViolated = apperror.New("PRODUCT_INVARIANT_VIOLATED", apperror.CategoryInternal, "Internal server error")
)
