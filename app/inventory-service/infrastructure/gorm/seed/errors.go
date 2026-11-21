package seed

import "inventoryservice/domain/apperror"

// ErrSeedFailure means a seed could not be applied - its cause is the error gorm returned. The service doesn't start
// then, and the seed's transaction leaves the database as it was.
var ErrSeedFailure = apperror.New("SEED_FAILURE", apperror.CategoryInternal, "Internal server error")
