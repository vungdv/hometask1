package server

import (
	"strings"

	"github.com/spf13/viper"
)

// LoadConfig reads environment variables and returns a Config struct.
func LoadConfig() (*Config, error) {
	var config *Config
	viper.SetConfigName("config")
	viper.SetConfigType("yaml")

	viper.SetEnvPrefix("GO_APP")
	viper.SetEnvKeyReplacer(strings.NewReplacer("-", "_"))
	viper.AutomaticEnv()

	configPaths := []string{"/etc/go_app", "$HOME/.go_app", "."}
	for _, path := range configPaths {
		viper.AddConfigPath(path)
	}

	if err := viper.ReadInConfig(); err != nil {
		return config, err
	}

	if err := viper.Unmarshal(&config); err != nil {
		return config, err
	}

	return config, nil
}
