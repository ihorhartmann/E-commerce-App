package apperror_test

import (
	"testing"

	"shippingservice/domain/apperror"
)

func TestCategory_String(t *testing.T) {
	tests := []struct {
		name     string
		category apperror.Category
		want     string
	}{
		{name: "names an internal failure", category: apperror.CategoryInternal, want: "INTERNAL"},
		{name: "names an invalid request", category: apperror.CategoryInvalid, want: "INVALID"},
		{name: "names a missing entity", category: apperror.CategoryNotFound, want: "NOT_FOUND"},
		{name: "names a business conflict", category: apperror.CategoryConflict, want: "CONFLICT"},
		{name: "names a transient failure", category: apperror.CategoryTransient, want: "TRANSIENT"},
		{name: "names an unavailable dependency", category: apperror.CategoryUnavailable, want: "UNAVAILABLE"},
		{name: "names an unknown value as internal", category: apperror.Category(99), want: "INTERNAL"},
	}
	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			if got := test.category.String(); got != test.want {
				t.Fatalf("got %q, want %q", got, test.want)
			}
		})
	}
}

func TestCategory_Permanent(t *testing.T) {
	tests := []struct {
		name     string
		category apperror.Category
		want     bool
	}{
		{name: "an invalid request is permanent", category: apperror.CategoryInvalid, want: true},
		{name: "a missing entity is permanent", category: apperror.CategoryNotFound, want: true},
		{name: "a business conflict is permanent", category: apperror.CategoryConflict, want: true},
		{name: "an internal failure is not permanent", category: apperror.CategoryInternal, want: false},
		{name: "a transient failure is not permanent", category: apperror.CategoryTransient, want: false},
		{name: "an unavailable dependency is not permanent", category: apperror.CategoryUnavailable, want: false},
	}
	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			if got := test.category.Permanent(); got != test.want {
				t.Fatalf("got %t, want %t", got, test.want)
			}
		})
	}
}

func TestCategory_Retryable(t *testing.T) {
	tests := []struct {
		name     string
		category apperror.Category
		want     bool
	}{
		{name: "a transient failure is retryable", category: apperror.CategoryTransient, want: true},
		{name: "an unavailable dependency is retryable", category: apperror.CategoryUnavailable, want: true},
		{name: "an internal failure is not retryable", category: apperror.CategoryInternal, want: false},
		{name: "an invalid request is not retryable", category: apperror.CategoryInvalid, want: false},
		{name: "a missing entity is not retryable", category: apperror.CategoryNotFound, want: false},
		{name: "a business conflict is not retryable", category: apperror.CategoryConflict, want: false},
	}
	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			if got := test.category.Retryable(); got != test.want {
				t.Fatalf("got %t, want %t", got, test.want)
			}
		})
	}
}
