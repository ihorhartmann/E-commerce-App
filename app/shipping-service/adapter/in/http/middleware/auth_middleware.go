package middleware

import (
	"net/http"
	"strings"

	inhttp "shippingservice/adapter/in/http"
	"shippingservice/adapter/in/http/auth"

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
// Register it after ErrorHandlerMiddleware (so its errors are formatted) and before IdempotencyMiddleware: a rejected
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
		abortUnauthorized(c, ErrMissingBearerToken)
		return
	}

	principal, err := m.verifier.Verify(c.Request.Context(), rawToken)
	if err != nil {
		abortUnauthorized(c, err)
		return
	}

	if !found || !principal.HasAnyRole(rule.Roles...) {
		_ = c.Error(inhttp.WithStatus(http.StatusForbidden, ErrAccessDenied))
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

// abortUnauthorized answers 401 with the WWW-Authenticate challenge RFC 6750 requires for Bearer tokens.
func abortUnauthorized(c *gin.Context, err error) {
	c.Header(wwwAuthenticateHeader, "Bearer")
	_ = c.Error(inhttp.WithStatus(http.StatusUnauthorized, err))
	c.Abort()
}
