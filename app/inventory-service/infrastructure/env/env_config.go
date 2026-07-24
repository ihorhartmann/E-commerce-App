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

	return &Config{
		Port:      port,
		KafkaHost: kafkaHost,
		KafkaPort: kafkaPort,
	}, nil
}

type Config struct {
	Port      string
	KafkaHost string
	KafkaPort string
}
