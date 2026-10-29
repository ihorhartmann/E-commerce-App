package gorm

import (
	"fmt"
	"inventoryservice/infrastructure/env"
	"inventoryservice/infrastructure/gorm/migration"
	"inventoryservice/infrastructure/telemetry"

	"github.com/uptrace/opentelemetry-go-extra/otelgorm"
	"go.opentelemetry.io/otel"
	"gorm.io/driver/postgres"
	"gorm.io/gorm"
)

// schema is the dedicated postgres schema this service owns.
const schema = "inventory"

func NewDB(config *env.Config) (*gorm.DB, error) {
	dsn := fmt.Sprintf(
		"host=%s port=%s user=%s password=%s dbname=%s sslmode=disable search_path=%s",
		config.DBHost, config.DBPort, config.DBUser, config.DBPassword, config.DBName, schema,
	)

	db, err := gorm.Open(postgres.Open(dsn), &gorm.Config{TranslateError: true})
	if err != nil {
		return nil, err
	}

	// a span per SQL statement, a child of whatever span the ctx given to db.WithContext carries, named
	// `{OPERATION} {table}` (see telemetry/span_names.go); the values bound to a query stay out of it
	if err = db.Use(otelgorm.NewPlugin(
		otelgorm.WithTracerProvider(telemetry.NewSQLSpanTracerProvider(otel.GetTracerProvider())),
		otelgorm.WithoutMetrics(),
		otelgorm.WithoutQueryVariables(),
	)); err != nil {
		return nil, err
	}

	if err = migration.Migrate(db); err != nil {
		return nil, err
	}

	return db, nil
}
