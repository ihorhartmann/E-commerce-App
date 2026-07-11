package env

func LoadFromEnv() (*Config, error) {
	port, err := GetStrEnv(PortEnvVar)
	if err != nil {
		return nil, err
	}

	return &Config{
		Port: port,
	}, nil
}

type Config struct {
	Port string
}
