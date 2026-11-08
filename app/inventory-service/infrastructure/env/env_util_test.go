package env

import (
	"log/slog"
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
