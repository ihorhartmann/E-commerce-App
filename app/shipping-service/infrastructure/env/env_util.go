package env

import (
	"errors"
	"log/slog"
	"os"
	"strconv"
)

const (
	envVarNotSetErrMsg       = " environment variable not set"
	invalidEnvVarValueErrMsg = " environment variable has invalid value"
)

func GetStrEnv(envVar string) (string, error) {
	val, ok := os.LookupEnv(envVar)
	if !ok {
		return "", errors.New(envVar + envVarNotSetErrMsg)
	}
	return val, nil
}

func GetIntEnv(envVar string) (int, error) {
	valStr, err := GetStrEnv(envVar)
	if err != nil {
		return 0, err
	}

	val, err := strconv.Atoi(valStr)
	if err != nil {
		return 0, errors.Join(errors.New(envVar+invalidEnvVarValueErrMsg), err)
	}

	return val, nil
}

// GetLogLevelEnv reads an optional log level - DEBUG, INFO, WARN or ERROR, in any case - and is fallback when envVar
// isn't set.
func GetLogLevelEnv(envVar string, fallback slog.Level) (slog.Level, error) {
	value, ok := os.LookupEnv(envVar)
	if !ok {
		return fallback, nil
	}

	var level slog.Level
	if err := level.UnmarshalText([]byte(value)); err != nil {
		return 0, errors.Join(errors.New(envVar+invalidEnvVarValueErrMsg), err)
	}

	return level, nil
}
