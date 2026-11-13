package env

import (
	"log/slog"
	"os"
	"path/filepath"
	"testing"
)

func TestGetLogLevelEnv(t *testing.T) {
	const envVar = "TEST_LOG_LEVEL"

	t.Run("is the fallback when the variable isn't set", func(t *testing.T) {
		fallback := slog.LevelInfo

		got, err := GetLogLevelEnv(envVar, fallback)

		if err != nil {
			t.Fatalf("GetLogLevelEnv: %v", err)
		}
		if got != fallback {
			t.Errorf("level = %v, want %v", got, fallback)
		}
	})

	t.Run("reads the level, in any case", func(t *testing.T) {
		t.Setenv(envVar, "debug")

		got, err := GetLogLevelEnv(envVar, slog.LevelInfo)

		if err != nil {
			t.Fatalf("GetLogLevelEnv: %v", err)
		}
		if want := slog.LevelDebug; got != want {
			t.Errorf("level = %v, want %v", got, want)
		}
	})

	t.Run("fails on a value that isn't a level", func(t *testing.T) {
		t.Setenv(envVar, "VERBOSE")

		if _, err := GetLogLevelEnv(envVar, slog.LevelInfo); err == nil {
			t.Error("GetLogLevelEnv: no error, want one")
		}
	})
}

func TestGetSecret(t *testing.T) {
	const envVar = "TEST_SECRET"

	writeSecretFile := func(t *testing.T, content string) string {
		t.Helper()
		dir := t.TempDir()
		if err := os.WriteFile(filepath.Join(dir, envVar), []byte(content), 0o600); err != nil {
			t.Fatalf("WriteFile: %v", err)
		}
		return dir
	}

	t.Run("reads the secret from the file named after the variable", func(t *testing.T) {
		want := "from-file"
		dir := writeSecretFile(t, want)
		t.Setenv(envVar, "from-env")

		got, err := getSecret(dir, envVar)

		if err != nil {
			t.Fatalf("getSecret: %v", err)
		}
		if got != want {
			t.Errorf("secret = %q, want %q", got, want)
		}
	})

	t.Run("drops the file's trailing line break", func(t *testing.T) {
		dir := writeSecretFile(t, "s3cret\r\n")

		got, err := getSecret(dir, envVar)

		if err != nil {
			t.Fatalf("getSecret: %v", err)
		}
		if want := "s3cret"; got != want {
			t.Errorf("secret = %q, want %q", got, want)
		}
	})

	t.Run("fails on a file that can't be read", func(t *testing.T) {
		dir := t.TempDir()
		// a directory in the file's place: it exists, but can't be read as a file
		if err := os.Mkdir(filepath.Join(dir, envVar), 0o700); err != nil {
			t.Fatalf("Mkdir: %v", err)
		}

		if _, err := getSecret(dir, envVar); err == nil {
			t.Error("getSecret: no error, want one")
		}
	})

	t.Run("fails on an empty file", func(t *testing.T) {
		dir := writeSecretFile(t, "\n")

		if _, err := getSecret(dir, envVar); err == nil {
			t.Error("getSecret: no error, want one")
		}
	})

	t.Run("reads the variable itself when there is no file", func(t *testing.T) {
		want := "from-env"
		t.Setenv(envVar, want)

		got, err := getSecret(t.TempDir(), envVar)

		if err != nil {
			t.Fatalf("getSecret: %v", err)
		}
		if got != want {
			t.Errorf("secret = %q, want %q", got, want)
		}
	})

	t.Run("fails when there is neither", func(t *testing.T) {
		if _, err := getSecret(t.TempDir(), envVar); err == nil {
			t.Error("getSecret: no error, want one")
		}
	})
}
