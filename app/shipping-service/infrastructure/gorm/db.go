package gorm

import (
	"fmt"
	"shippingservice/infrastructure/env"
	"shippingservice/infrastructure/gorm/migration"

	"github.com/uptrace/opentelemetry-go-extra/otelgorm"
	"gorm.io/driver/postgres"
	"gorm.io/gorm"
)

// schema is the dedicated postgres schema this service owns.
const schema = "shipping"

func NewDB(config *env.Config) (*gorm.DB, error) {
	dsn := fmt.Sprintf(
		"host=%s port=%s user=%s password=%s dbname=%s sslmode=disable search_path=%s",
		config.DBHost, config.DBPort, config.DBUser, config.DBPassword, config.DBName, schema,
	)

	db, err := gorm.Open(postgres.Open(dsn), &gorm.Config{TranslateError: true})
	if err != nil {
		return nil, err
	}

	// a span per SQL statement, a child of whatever span the ctx given to db.WithContext carries; the values bound to a
	// query stay out of it
	if err = db.Use(otelgorm.NewPlugin(otelgorm.WithoutMetrics(), otelgorm.WithoutQueryVariables())); err != nil {
		return nil, err
	}

	if err = migration.Migrate(db); err != nil {
		return nil, err
	}

	return db, nil
}
