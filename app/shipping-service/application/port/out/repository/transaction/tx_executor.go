package transaction

import "context"

type Executor interface {
	Transaction(ctx context.Context, action func(ctx context.Context) error) error
}
