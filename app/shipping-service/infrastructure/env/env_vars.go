package env

const (
	PortEnvVar                     = "SHIPPING_SERVICE_PORT"
	KafkaHostEnvVar                = "KAFKA_HOST"
	KafkaPortEnvVar                = "KAFKA_PORT"
	DBHostEnvVar                   = "DB_HOST"
	DBPortEnvVar                   = "DB_PORT"
	DBNameEnvVar                   = "DB_NAME"
	DBUserEnvVar                   = "DB_USER"
	DBPasswordEnvVar               = "DB_PASSWORD"
	OIDCIssuerEnvVar               = "OIDC_ISSUER"
	OIDCJWKSURIEnvVar              = "OIDC_JWKS_URI"
	OIDCRolesClaimEnvVar           = "OIDC_ROLES_CLAIM"
	OTELExporterOTLPEndpointEnvVar = "OTEL_EXPORTER_OTLP_ENDPOINT"
	OTELServiceNameEnvVar          = "OTEL_SERVICE_NAME"
)
