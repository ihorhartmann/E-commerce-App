package value

import "shippingservice/domain/apperror"

var ErrInvalidAddress = apperror.New("INVALID_ADDRESS", apperror.CategoryInvalid, "City, postal code and the first address line are required")
