package env

import (
	"errors"
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
