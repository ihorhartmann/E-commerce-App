package auth

import "context"

//go:generate go run go.uber.org/mock/mockgen -source=token_verifier.go -destination=mocks/mock_token_verifier.go -package=mocks

// TokenVerifier checks a raw Bearer access token (signature, issuer, expiry) and returns who it belongs to.
type TokenVerifier interface {
	// Verify returns ErrInvalidToken (wrapped with the reason) for any token that must not be trusted.
	Verify(ctx context.Context, rawToken string) (Principal, error)
}
