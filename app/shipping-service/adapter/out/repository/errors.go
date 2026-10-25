package repository

import "shippingservice/domain/apperror"

// ErrDatabaseFailure is what the Postgres repositories report a database error with - its cause is
// the error gorm returned. It is not part of the repository ports: no caller tells it apart from any
// other internal failure.
var ErrDatabaseFailure = apperror.New("DATABASE_FAILURE", apperror.CategoryInternal, "Internal server error")
