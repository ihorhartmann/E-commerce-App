package env

import "log/slog"

func LoadFromEnv() (*Config, error) {
	port, err := GetStrEnv(PortEnvVar)
	if err != nil {
		return nil, err
	}

	kafkaHost, err := GetStrEnv(KafkaHostEnvVar)
	if err != nil {
		return nil, err
	}

	kafkaPort, err := GetStrEnv(KafkaPortEnvVar)
	if err != nil {
		return nil, err
	}

	dbHost, err := GetStrEnv(DBHostEnvVar)
	if err != nil {
		return nil, err
	}

	dbPort, err := GetStrEnv(DBPortEnvVar)
	if err != nil {
		return nil, err
	}

	dbName, err := GetStrEnv(DBNameEnvVar)
	if err != nil {
		return nil, err
	}

	dbUser, err := GetStrEnv(DBUserEnvVar)
	if err != nil {
		return nil, err
	}

	dbPassword, err := GetStrEnv(DBPasswordEnvVar)
	if err != nil {
		return nil, err
	}

	oidcIssuer, err := GetStrEnv(OIDCIssuerEnvVar)
	if err != nil {
		return nil, err
	}

	oidcJWKSURI, err := GetStrEnv(OIDCJWKSURIEnvVar)
	if err != nil {
		return nil, err
	}

	oidcRolesClaim, err := GetStrEnv(OIDCRolesClaimEnvVar)
	if err != nil {
		return nil, err
	}

	otelExporterOTLPEndpoint, err := GetStrEnv(OTELExporterOTLPEndpointEnvVar)
	if err != nil {
		return nil, err
	}

	otelServiceName, err := GetStrEnv(OTELServiceNameEnvVar)
	if err != nil {
		return nil, err
	}

	logLevel, err := GetLogLevelEnv(LogLevelEnvVar, slog.LevelInfo)
	if err != nil {
		return nil, err
	}

	return &Config{
		Port:                     port,
		KafkaHost:                kafkaHost,
		KafkaPort:                kafkaPort,
		DBHost:                   dbHost,
		DBPort:                   dbPort,
		DBName:                   dbName,
		DBUser:                   dbUser,
		DBPassword:               dbPassword,
		OIDCIssuer:               oidcIssuer,
		OIDCJWKSURI:              oidcJWKSURI,
		OIDCRolesClaim:           oidcRolesClaim,
		OTELExporterOTLPEndpoint: otelExporterOTLPEndpoint,
		OTELServiceName:          otelServiceName,
		LogLevel:                 logLevel,
	}, nil
}

type Config struct {
	Port       string
	KafkaHost  string
	KafkaPort  string
	DBHost     string
	DBPort     string
	DBName     string
	DBUser     string
	DBPassword string
	// OIDCIssuer is the public Keycloak realm URL - equal to `iss` in every access token
	OIDCIssuer string
	// OIDCJWKSURI is where the realm's public keys are fetched from, inside the network
	OIDCJWKSURI string
	// OIDCRolesClaim is the dot-separated path to the roles array inside the token, e.g. realm_access.roles
	OIDCRolesClaim string
	// OTELExporterOTLPEndpoint is the collector's OTLP/HTTP base URL, e.g. http://alloy:4318
	OTELExporterOTLPEndpoint string
	// OTELServiceName is the service.name every span of this service carries
	OTELServiceName string
	// LogLevel is the lowest level logged - INFO unless LOG_LEVEL says otherwise
	LogLevel slog.Level
}
