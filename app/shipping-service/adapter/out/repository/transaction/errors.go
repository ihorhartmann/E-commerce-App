package transaction

import "shippingservice/domain/apperror"

// The failures of the gorm transaction support. They are not part of the transaction port: no caller
// tells them apart from any other internal failure.
var (
	// ErrTransactionFailure means the transaction itself failed to begin or commit - its cause is
	// the error gorm returned. A failure of the action run inside it is reported as it is.
	ErrTransactionFailure = apperror.New("TRANSACTION_FAILURE", apperror.CategoryInternal, "Internal server error")
	// ErrNoTransaction means code that must run inside a transaction was called outside one - a bug.
	ErrNoTransaction = apperror.New("NO_TRANSACTION", apperror.CategoryInternal, "Internal server error")
)
