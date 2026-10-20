package middleware

import (
	"log/slog"
	"strings"

	"shippingservice/adapter/in/http/auth"
	"shippingservice/adapter/in/http/httperror"

	"github.com/gin-gonic/gin"
)

const (
	authorizationHeader   = "Authorization"
	wwwAuthenticateHeader = "WWW-Authenticate"
	bearerPrefix          = "Bearer "
)

// AuthMiddleware decides, before any handler runs, whether the caller may make the request: it finds the first rule
// in accessRules matching the method and path, and either lets it through, or requires a valid Bearer access token
// with one of the rule's roles. A request no rule matches is denied - an endpoint has to be opened explicitly.
//
// Register it below HandleMiddlewareError (so its errors are written) and above IdempotencyMiddleware: a rejected
// request must not claim its Idempotency-Key, otherwise the same request retried with a valid token would get
// "already in progress" until the claim expires.
type AuthMiddleware struct {
	verifier auth.TokenVerifier
}

func NewAuthMiddleware(verifier auth.TokenVerifier) *AuthMiddleware {
	return &AuthMiddleware{verifier: verifier}
}

func (m *AuthMiddleware) Handle(c *gin.Context) {
	rule, found := findRule(c.Request.Method, c.Request.URL.Path)
	if found && rule.PermitAll {
		c.Next()
		return
	}

	rawToken, hasToken := strings.CutPrefix(c.GetHeader(authorizationHeader), bearerPrefix)
	if !hasToken || rawToken == "" {
		abortUnauthorized(c, httperror.ErrMissingBearerToken)
		return
	}

	principal, err := m.verifier.Verify(c.Request.Context(), rawToken)
	if err != nil {
		abortUnauthorized(c, err)
		return
	}

	if !found || !principal.HasAnyRole(rule.Roles...) {
		_ = c.Error(httperror.ErrAccessDenied.
			WithInternalMessage("caller has none of the roles the endpoint requires").
			WithAttrs(slog.String("userID", principal.UserID.String()), slog.String("method", c.Request.Method), slog.String("path", c.Request.URL.Path)))
		c.Abort()
		return
	}

	c.Set(auth.PrincipalContextKey, principal)
	c.Next()
}

func findRule(method, path string) (auth.AccessRule, bool) {
	for _, rule := range accessRules() {
		if rule.Matches(method, path) {
			return rule, true
		}
	}
	return auth.AccessRule{}, false
}

// abortUnauthorized adds the WWW-Authenticate challenge RFC 6750 requires for Bearer tokens to a 401.
func abortUnauthorized(c *gin.Context, err error) {
	c.Header(wwwAuthenticateHeader, "Bearer")
	_ = c.Error(err)
	c.Abort()
}
