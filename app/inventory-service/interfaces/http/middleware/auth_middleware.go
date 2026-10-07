package middleware

import (
	"net/http"
	"strings"

	"inventoryservice/interfaces/http/auth"
	httperr "inventoryservice/interfaces/http/errors"

	"github.com/gofiber/fiber/v2"
)

const (
	bearerPrefix = "Bearer "
	// principalLocalsKey is where the authenticated caller is stored for the handlers (fiber.Ctx.Locals).
	principalLocalsKey = "principal"
)

// AuthMiddleware decides, before any handler runs, whether the caller may make the request: it finds the first rule
// in accessRules matching the method and path, and either lets it through, or requires a valid Bearer access token
// with one of the rule's roles. A request no rule matches is denied - an endpoint has to be opened explicitly.
//
// It has to be registered before IdempotencyMiddleware: a rejected request must not claim its Idempotency-Key,
// otherwise the same request retried with a valid token would get "already in progress" until the claim expires.
type AuthMiddleware struct {
	verifier auth.TokenVerifier
}

func NewAuthMiddleware(verifier auth.TokenVerifier) *AuthMiddleware {
	return &AuthMiddleware{verifier: verifier}
}

func (m *AuthMiddleware) Handle(c *fiber.Ctx) error {
	rule, found := findRule(c.Method(), c.Path())
	if found && rule.PermitAll {
		return c.Next()
	}

	rawToken, hasToken := strings.CutPrefix(c.Get(fiber.HeaderAuthorization), bearerPrefix)
	if !hasToken || rawToken == "" {
		return unauthorized(c, ErrMissingBearerToken)
	}

	principal, err := m.verifier.Verify(c.Context(), rawToken)
	if err != nil {
		return unauthorized(c, err)
	}

	if !found || !principal.HasAnyRole(rule.Roles...) {
		return httperr.WithStatus(http.StatusForbidden, ErrAccessDenied)
	}

	c.Locals(principalLocalsKey, principal)
	return c.Next()
}

func findRule(method, path string) (auth.AccessRule, bool) {
	for _, rule := range accessRules() {
		if rule.Matches(method, path) {
			return rule, true
		}
	}
	return auth.AccessRule{}, false
}

// unauthorized answers 401 with the WWW-Authenticate challenge RFC 6750 requires for Bearer tokens.
func unauthorized(c *fiber.Ctx, err error) error {
	c.Set(fiber.HeaderWWWAuthenticate, "Bearer")
	return httperr.WithStatus(http.StatusUnauthorized, err)
}
