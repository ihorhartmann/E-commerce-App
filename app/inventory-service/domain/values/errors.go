package values

import "inventoryservice/domain/apperror"

var (
	ErrInvalidMoneyAmount  = apperror.New("INVALID_MONEY_AMOUNT", apperror.CategoryInvalid, "Amount must be a non-negative number with at most two decimal places")
	ErrUnsupportedCurrency = apperror.New("UNSUPPORTED_CURRENCY", apperror.CategoryInvalid, "Unsupported currency")
)
