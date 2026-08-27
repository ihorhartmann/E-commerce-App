package env

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

	return &Config{
		Port:       port,
		KafkaHost:  kafkaHost,
		KafkaPort:  kafkaPort,
		DBHost:     dbHost,
		DBPort:     dbPort,
		DBName:     dbName,
		DBUser:     dbUser,
		DBPassword: dbPassword,
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
}
