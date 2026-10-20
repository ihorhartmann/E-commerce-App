package apperror

// Category says what a caller can do about an error. It alone decides the HTTP status, whether a
// Kafka consumer retries the message or dead-letters it at once, and whether the idempotency
// middleware caches the response or releases the key.
type Category uint8

const (
	// CategoryInternal is an unexpected failure - a bug, or a library error nobody classified: 500,
	// retried by Kafka consumers, idempotency key released.
	CategoryInternal Category = iota
	// CategoryInvalid is a request or message that breaks a validation rule: 400.
	CategoryInvalid
	// CategoryNotFound is a referenced entity that doesn't exist (or belongs to someone else): 404.
	CategoryNotFound
	// CategoryConflict is a business rule rejecting the operation in the entity's current state: 409.
	CategoryConflict
	// CategoryTransient is a failure that repeating the same request is expected to fix, e.g. a
	// concurrent update that exhausted the service's own retries: 503 + Retry-After.
	CategoryTransient
	// CategoryUnavailable is a dependency (database, another service, a third party) that can't be
	// reached: 503 + Retry-After.
	CategoryUnavailable
)

func (c Category) String() string {
	switch c {
	case CategoryInvalid:
		return "INVALID"
	case CategoryNotFound:
		return "NOT_FOUND"
	case CategoryConflict:
		return "CONFLICT"
	case CategoryTransient:
		return "TRANSIENT"
	case CategoryUnavailable:
		return "UNAVAILABLE"
	default:
		return "INTERNAL"
	}
}

// Permanent reports whether repeating the same request or message can't change the outcome: a
// Kafka consumer dead-letters it without retrying, and the idempotency middleware caches it.
func (c Category) Permanent() bool {
	return c == CategoryInvalid || c == CategoryNotFound || c == CategoryConflict
}

// Retryable reports whether the caller is invited to repeat the same request as it is, with the
// same Idempotency-Key. CategoryInternal is neither permanent nor retryable: it is retried by Kafka
// consumers, but an HTTP caller isn't told that retrying will help.
func (c Category) Retryable() bool {
	return c == CategoryTransient || c == CategoryUnavailable
}
