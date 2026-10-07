package auth

import "slices"

// Principal is the authenticated caller, taken from a verified access token.
type Principal struct {
	// UserID is the token's `sub` - the Keycloak user's stable id.
	UserID string
	Roles  []string
}

func (p Principal) HasAnyRole(roles ...string) bool {
	return slices.ContainsFunc(roles, func(role string) bool { return slices.Contains(p.Roles, role) })
}
