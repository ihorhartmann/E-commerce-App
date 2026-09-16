package transaction

import "context"

type Executor interface {
	Transaction(ctx context.Context, action func(ctx context.Context) error) error
}

// Execute wraps Executor.Transaction to capture and return a typed result, instead of the caller
// declaring a var above the closure and assigning into it by hand.
func Execute[T any](ctx context.Context, executor Executor, action func(ctx context.Context) (T, error)) (T, error) {
	var result T
	err := executor.Transaction(ctx, func(ctx context.Context) error {
		var err error
		result, err = action(ctx)
		return err
	})
	return result, err
}
