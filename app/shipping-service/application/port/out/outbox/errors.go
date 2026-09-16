package outbox

import "errors"

var ErrOutboxMessageNotFound = errors.New("outbox message not found")
