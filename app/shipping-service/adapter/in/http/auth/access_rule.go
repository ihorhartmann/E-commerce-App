package auth

import "strings"

// pathWildcard matches exactly one path segment, e.g. "/api/v1/products/*" matches "/api/v1/products/1".
const pathWildcard = "*"

// AccessRule says who may call an endpoint: anyone (PermitAll), or a caller with one of the listed roles
// (RequireRole). Rules are evaluated before routing, so they match on the raw method and path.
type AccessRule struct {
	Method    string
	Path      string
	PermitAll bool
	Roles     []string
}

// PermitAll lets anyone call the endpoint, without a token.
func PermitAll(method, path string) AccessRule {
	return AccessRule{Method: method, Path: path, PermitAll: true}
}

// RequireRole lets only a caller with at least one of the roles call the endpoint.
func RequireRole(method, path string, roles ...string) AccessRule {
	return AccessRule{Method: method, Path: path, Roles: roles}
}

// Matches reports whether the rule applies to a request; a trailing slash on the request path is ignored.
func (r AccessRule) Matches(method, path string) bool {
	if r.Method != method {
		return false
	}

	ruleSegments := splitPath(r.Path)
	pathSegments := splitPath(path)
	if len(ruleSegments) != len(pathSegments) {
		return false
	}

	for i, segment := range ruleSegments {
		if segment != pathWildcard && segment != pathSegments[i] {
			return false
		}
	}
	return true
}

func splitPath(path string) []string {
	return strings.Split(strings.Trim(path, "/"), "/")
}
