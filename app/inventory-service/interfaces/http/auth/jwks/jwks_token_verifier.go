package jwks

import (
	"context"
	"fmt"
	"strings"

	"inventoryservice/interfaces/http/auth"
	"inventoryservice/interfaces/http/httperror"

	"github.com/MicahParks/keyfunc/v3"
	"github.com/golang-jwt/jwt/v5"
	"github.com/google/uuid"
)

// signingAlgorithm is the only algorithm accepted - Keycloak signs access tokens with RS256 by default, and pinning it
// rules out algorithm-confusion attacks ("alg": "none", HS256 with the public key as the secret).
const signingAlgorithm = "RS256"

// JWKSTokenVerifier verifies Keycloak access tokens against the realm's public keys (JWKS). The keys are cached and
// refreshed in the background, and re-fetched when a token carries an unknown key id (key rotation), so a request
// never calls Keycloak itself.
type JWKSTokenVerifier struct {
	auth.TokenVerifier

	keyfunc        jwt.Keyfunc
	parser         *jwt.Parser
	rolesClaimPath []string
}

// NewJWKSTokenVerifier starts the background JWKS refresh, which stops when ctx is cancelled. A Keycloak that isn't up
// yet doesn't fail the startup - the keys are fetched again on the first token.
func NewJWKSTokenVerifier(ctx context.Context, jwksURI, issuer, rolesClaim string) (auth.TokenVerifier, error) {
	jwks, err := keyfunc.NewDefaultCtx(ctx, []string{jwksURI})
	if err != nil {
		return nil, fmt.Errorf("create JWKS client for %s: %w", jwksURI, err)
	}
	return newJWKSTokenVerifier(jwks.Keyfunc, issuer, rolesClaim), nil
}

func newJWKSTokenVerifier(keyfunc jwt.Keyfunc, issuer, rolesClaim string) *JWKSTokenVerifier {
	return &JWKSTokenVerifier{
		keyfunc: keyfunc,
		parser: jwt.NewParser(
			jwt.WithValidMethods([]string{signingAlgorithm}),
			jwt.WithIssuer(issuer),
			jwt.WithExpirationRequired(),
		),
		rolesClaimPath: strings.Split(rolesClaim, "."),
	}
}

func (v *JWKSTokenVerifier) Verify(_ context.Context, rawToken string) (auth.Principal, error) {
	claims := jwt.MapClaims{}
	if _, err := v.parser.ParseWithClaims(rawToken, claims, v.keyfunc); err != nil {
		return auth.Principal{}, httperror.ErrInvalidToken.WithInternalMessage("parse access token").WithCause(err)
	}

	subject, err := claims.GetSubject()
	if err != nil || subject == "" {
		return auth.Principal{}, httperror.ErrInvalidToken.WithInternalMessage("access token has no sub claim")
	}

	// Keycloak user ids are UUIDs - anything else means the token can't name a customer or courier
	userID, err := uuid.Parse(subject)
	if err != nil {
		return auth.Principal{}, httperror.ErrInvalidToken.WithInternalMessage("access token sub is not a UUID").WithCause(err)
	}

	return auth.Principal{UserID: userID, Roles: v.roles(claims)}, nil
}

// roles reads the string array at the configured claim path (e.g. realm_access.roles); anything else means no roles.
func (v *JWKSTokenVerifier) roles(claims jwt.MapClaims) []string {
	var node any = map[string]any(claims)
	for _, key := range v.rolesClaimPath {
		obj, ok := node.(map[string]any)
		if !ok {
			return nil
		}
		node = obj[key]
	}

	values, ok := node.([]any)
	if !ok {
		return nil
	}

	roles := make([]string, 0, len(values))
	for _, value := range values {
		if role, ok := value.(string); ok {
			roles = append(roles, role)
		}
	}
	return roles
}
