package env

import (
	"errors"
	"io/fs"
	"log/slog"
	"os"
	"path/filepath"
	"strconv"
	"strings"
)

const (
	envVarNotSetErrMsg         = " environment variable not set"
	invalidEnvVarValueErrMsg   = " environment variable has invalid value"
	unreadableSecretFileErrMsg = " can't be read"
	emptySecretFileErrMsg      = " is empty"

	// secretsDir is where Docker mounts the secrets of a container
	secretsDir = "/run/secrets"
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

// GetSecretEnv reads a secret from its Docker secret, the file /run/secrets/<envVar> - so that the value is in no
// environment variable - or, when there is no such file, from envVar itself (a service started outside Docker).
func GetSecretEnv(envVar string) (string, error) {
	return getSecret(secretsDir, envVar)
}

// getSecret reads the secret envVar from the file of that name in dir, falling back to the variable. The file's
// trailing line break, which an editor adds, is no part of the secret; an empty file is an error.
func getSecret(dir string, envVar string) (string, error) {
	path := filepath.Join(dir, envVar)
	content, err := os.ReadFile(path)
	if errors.Is(err, fs.ErrNotExist) {
		return GetStrEnv(envVar)
	}
	if err != nil {
		return "", errors.Join(errors.New(path+unreadableSecretFileErrMsg), err)
	}

	secret := strings.TrimRight(string(content), "\r\n")
	if secret == "" {
		return "", errors.New(path + emptySecretFileErrMsg)
	}

	return secret, nil
}
