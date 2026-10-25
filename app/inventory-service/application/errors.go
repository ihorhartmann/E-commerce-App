package application

import "inventoryservice/domain/apperror"

var (
	ErrInvalidProductID = apperror.New("INVALID_PRODUCT_ID", apperror.CategoryInvalid, "Product id must be positive")
	ErrInvalidPage      = apperror.New("INVALID_PAGE", apperror.CategoryInvalid, "Invalid page or page size")
)
