package gorm

import (
	"fmt"
	"log/slog"
	"shippingservice/infrastructure/env"
	"shippingservice/infrastructure/gorm/migration"
	"shippingservice/infrastructure/telemetry"
	"time"

	"github.com/uptrace/opentelemetry-go-extra/otelgorm"
	"go.opentelemetry.io/otel"
	"gorm.io/driver/postgres"
	"gorm.io/gorm"
	"gorm.io/gorm/logger"
)

// schema is the dedicated postgres schema this service owns.
const schema = "shipping"

// slowStatementThreshold is how long a statement runs before GORM logs it as slow - its own default.
const slowStatementThreshold = 200 * time.Millisecond

func NewDB(config *env.Config) (*gorm.DB, error) {
	dsn := fmt.Sprintf(
		"host=%s port=%s user=%s password=%s dbname=%s sslmode=disable search_path=%s",
		config.DBHost, config.DBPort, config.DBUser, config.DBPassword, config.DBName, schema,
	)

	// GORM's own lines - a failed or a slow statement - go through the slog handler main.go installs, as JSON like any
	// other line: the statement as prepared, never the values bound to it
	gormLog := logger.NewSlogLogger(slog.Default(), logger.Config{
		LogLevel:                  logger.Warn,
		SlowThreshold:             slowStatementThreshold,
		ParameterizedQueries:      true,
		IgnoreRecordNotFoundError: true,
	})
	db, err := gorm.Open(postgres.Open(dsn), &gorm.Config{TranslateError: true, Logger: gormLog})
	if err != nil {
		return nil, err
	}

	// a span per SQL statement, a child of whatever span the ctx given to db.WithContext carries, named
	// `{OPERATION} {table}` (see telemetry/span_names.go); the values bound to a query stay out of it
	// The plugin also reports the connection pool's metrics - open, in use, idle, waits - to the global meter provider.
	if err = db.Use(otelgorm.NewPlugin(
		otelgorm.WithTracerProvider(telemetry.NewSQLSpanTracerProvider(otel.GetTracerProvider())),
		otelgorm.WithoutQueryVariables(),
	)); err != nil {
		return nil, err
	}

	if err = migration.Migrate(db); err != nil {
		return nil, err
	}

	return db, nil
}
