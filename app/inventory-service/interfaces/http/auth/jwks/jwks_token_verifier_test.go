package jwks

import (
	"context"
	"crypto/rand"
	"crypto/rsa"
	"errors"
	"slices"
	"testing"
	"time"

	"inventoryservice/interfaces/http/auth"

	"github.com/golang-jwt/jwt/v5"
	"github.com/google/uuid"
)

const (
	testIssuer     = "http://localhost:8090/realms/ecommerce"
	testRolesClaim = "realm_access.roles"
)

func generateKey(t *testing.T) *rsa.PrivateKey {
	t.Helper()
	key, err := rsa.GenerateKey(rand.Reader, 2048)
	if err != nil {
		t.Fatalf("failed to generate RSA key: %v", err)
	}
	return key
}

func staticKeyfunc(key *rsa.PrivateKey) jwt.Keyfunc {
	return func(*jwt.Token) (any, error) { return &key.PublicKey, nil }
}

func signToken(t *testing.T, key *rsa.PrivateKey, claims jwt.MapClaims) string {
	t.Helper()
	signed, err := jwt.NewWithClaims(jwt.SigningMethodRS256, claims).SignedString(key)
	if err != nil {
		t.Fatalf("failed to sign token: %v", err)
	}
	return signed
}

func validClaims(subject string) jwt.MapClaims {
	return jwt.MapClaims{
		"iss":          testIssuer,
		"sub":          subject,
		"exp":          time.Now().Add(time.Minute).Unix(),
		"realm_access": map[string]any{"roles": []string{"ADMIN", "offline_access"}},
	}
}

func TestJWKSTokenVerifier_Verify(t *testing.T) {
	t.Run("returns the subject and the roles of a valid token", func(t *testing.T) {
		key := generateKey(t)
		subject := uuid.New()
		verifier := newJWKSTokenVerifier(staticKeyfunc(key), testIssuer, testRolesClaim)

		got, err := verifier.Verify(context.Background(), signToken(t, key, validClaims(subject.String())))

		if err != nil {
			t.Fatalf("unexpected error: %v", err)
		}
		if got.UserID != subject {
			t.Errorf("UserID = %q, want %q", got.UserID, subject)
		}
		if !slices.Equal(got.Roles, []string{"ADMIN", "offline_access"}) {
			t.Errorf("Roles = %v, want [ADMIN offline_access]", got.Roles)
		}
	})

	t.Run("returns no roles when the roles claim is missing", func(t *testing.T) {
		key := generateKey(t)
		claims := validClaims(uuid.NewString())
		delete(claims, "realm_access")
		verifier := newJWKSTokenVerifier(staticKeyfunc(key), testIssuer, testRolesClaim)

		got, err := verifier.Verify(context.Background(), signToken(t, key, claims))

		if err != nil {
			t.Fatalf("unexpected error: %v", err)
		}
		if len(got.Roles) != 0 {
			t.Errorf("Roles = %v, want none", got.Roles)
		}
	})

	t.Run("rejects a token signed by another key", func(t *testing.T) {
		verifier := newJWKSTokenVerifier(staticKeyfunc(generateKey(t)), testIssuer, testRolesClaim)

		_, err := verifier.Verify(context.Background(), signToken(t, generateKey(t), validClaims(uuid.NewString())))

		if !errors.Is(err, auth.ErrInvalidToken) {
			t.Errorf("err = %v, want ErrInvalidToken", err)
		}
	})

	t.Run("rejects a token from another issuer", func(t *testing.T) {
		key := generateKey(t)
		claims := validClaims(uuid.NewString())
		claims["iss"] = "http://evil.example/realms/ecommerce"
		verifier := newJWKSTokenVerifier(staticKeyfunc(key), testIssuer, testRolesClaim)

		_, err := verifier.Verify(context.Background(), signToken(t, key, claims))

		if !errors.Is(err, auth.ErrInvalidToken) {
			t.Errorf("err = %v, want ErrInvalidToken", err)
		}
	})

	t.Run("rejects an expired token", func(t *testing.T) {
		key := generateKey(t)
		claims := validClaims(uuid.NewString())
		claims["exp"] = time.Now().Add(-time.Minute).Unix()
		verifier := newJWKSTokenVerifier(staticKeyfunc(key), testIssuer, testRolesClaim)

		_, err := verifier.Verify(context.Background(), signToken(t, key, claims))

		if !errors.Is(err, auth.ErrInvalidToken) {
			t.Errorf("err = %v, want ErrInvalidToken", err)
		}
	})

	t.Run("rejects a token without an expiry", func(t *testing.T) {
		key := generateKey(t)
		claims := validClaims(uuid.NewString())
		delete(claims, "exp")
		verifier := newJWKSTokenVerifier(staticKeyfunc(key), testIssuer, testRolesClaim)

		_, err := verifier.Verify(context.Background(), signToken(t, key, claims))

		if !errors.Is(err, auth.ErrInvalidToken) {
			t.Errorf("err = %v, want ErrInvalidToken", err)
		}
	})

	t.Run("rejects a token signed with another algorithm", func(t *testing.T) {
		key := generateKey(t)
		hmacToken, err := jwt.NewWithClaims(jwt.SigningMethodHS256, validClaims(uuid.NewString())).SignedString([]byte("secret"))
		if err != nil {
			t.Fatalf("failed to sign token: %v", err)
		}
		verifier := newJWKSTokenVerifier(staticKeyfunc(key), testIssuer, testRolesClaim)

		_, err = verifier.Verify(context.Background(), hmacToken)

		if !errors.Is(err, auth.ErrInvalidToken) {
			t.Errorf("err = %v, want ErrInvalidToken", err)
		}
	})

	t.Run("rejects a token without a subject", func(t *testing.T) {
		key := generateKey(t)
		claims := validClaims(uuid.NewString())
		delete(claims, "sub")
		verifier := newJWKSTokenVerifier(staticKeyfunc(key), testIssuer, testRolesClaim)

		_, err := verifier.Verify(context.Background(), signToken(t, key, claims))

		if !errors.Is(err, auth.ErrInvalidToken) {
			t.Errorf("err = %v, want ErrInvalidToken", err)
		}
	})

	t.Run("rejects a token whose subject is not a UUID", func(t *testing.T) {
		key := generateKey(t)
		verifier := newJWKSTokenVerifier(staticKeyfunc(key), testIssuer, testRolesClaim)

		_, err := verifier.Verify(context.Background(), signToken(t, key, validClaims("not-a-uuid")))

		if !errors.Is(err, auth.ErrInvalidToken) {
			t.Errorf("err = %v, want ErrInvalidToken", err)
		}
	})

	t.Run("rejects a malformed token", func(t *testing.T) {
		verifier := newJWKSTokenVerifier(staticKeyfunc(generateKey(t)), testIssuer, testRolesClaim)

		_, err := verifier.Verify(context.Background(), "not-a-jwt")

		if !errors.Is(err, auth.ErrInvalidToken) {
			t.Errorf("err = %v, want ErrInvalidToken", err)
		}
	})
}
