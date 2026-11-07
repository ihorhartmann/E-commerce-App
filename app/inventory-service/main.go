package main

import (
	"context"
	"inventoryservice/application/port/common/kafka"
	"inventoryservice/infrastructure/idempotency/store"
	"inventoryservice/interfaces/http/auth/jwks"
	"inventoryservice/interfaces/http/middleware"
	"inventoryservice/interfaces/kafka/kafkago"
	"inventoryservice/interfaces/scheduled"
	"log/slog"
	"os"
	"os/signal"
	"syscall"
	"time"

	"inventoryservice/application"
	"inventoryservice/infrastructure/env"
	gormconn "inventoryservice/infrastructure/gorm"
	gormtx "inventoryservice/infrastructure/gorm/transaction"
	"inventoryservice/infrastructure/repository"
	"inventoryservice/infrastructure/telemetry"
	inhttp "inventoryservice/interfaces/http"
	"inventoryservice/interfaces/http/idempotency"
	inkafka "inventoryservice/interfaces/kafka"

	"github.com/gofiber/contrib/otelfiber/v2"
	"github.com/gofiber/fiber/v2"
	"gorm.io/gorm"
)

const (
	// how long shutdown waits for the buffered spans and the last metrics to reach the collector
	telemetryShutdownTimeout = 5 * time.Second
)

func main() {
	// JSON lines on stdout, for Alloy to push to Loki; a line logged with a ctx gets its trace and its logging.WithAttrs.
	// INFO until the configuration says which level - a configuration that fails to load is logged as JSON too.
	logLevel := new(slog.LevelVar)
	slog.SetDefault(slog.New(telemetry.NewLogHandler(os.Stdout, logLevel)))

	config, err := env.LoadFromEnv()
	if err != nil {
		slog.Error("failed to load configuration", "error", err)
		os.Exit(1)
	}
	logLevel.Set(config.LogLevel)

	// first, so the database, Kafka and HTTP instrumentation below all find the provider installed
	shutdownTracing, err := telemetry.InitTracing(context.Background(), config.OTELExporterOTLPEndpoint, config.OTELServiceName)
	if err != nil {
		slog.Error("failed to set up tracing", "error", err)
		os.Exit(1)
	}
	shutdownMetrics, err := telemetry.InitMetrics(context.Background(), config.OTELExporterOTLPEndpoint, config.OTELServiceName)
	if err != nil {
		slog.Error("failed to set up metrics", "error", err)
		os.Exit(1)
	}

	db, err := gormconn.NewDB(config)
	if err != nil {
		slog.Error("failed to connect to database", "error", err)
		os.Exit(1)
	}
	sqlDB, err := db.DB()
	if err != nil {
		slog.Error("failed to get database connection pool", "error", err)
		os.Exit(1)
	}

	productRepository := repository.NewPostgresProductRepository(db)
	reservationRepository := repository.NewPostgresReservationRepository(db)
	txExecutor := gormtx.NewGormTxManager(db)
	processedMessageStore := store.NewPostgresProcessedKafkaMessageStore(db)
	httpRequestStore := store.NewPostgresHTTPRequestStore(db)
	idempotencyService := idempotency.NewHTTPIdempotencyService(httpRequestStore)

	productUseCases := application.NewProductService(productRepository, processedMessageStore, txExecutor)
	reservationUseCases := application.NewReservationService(reservationRepository, productRepository, processedMessageStore, txExecutor)

	brokers := []string{config.KafkaHost + ":" + config.KafkaPort}
	if err = kafkago.CreateTopics(context.Background(), brokers); err != nil {
		slog.Error("failed to declare kafka topics", "error", err)
		closeDB(db)
		os.Exit(1)
	}

	kafkaConsumer := kafkago.NewKafkaGoMessageConsumer(brokers, kafka.InventoryCommandsTopic, kafka.ConsumerGroupID)
	reservationConsumer := inkafka.NewReservationKafkaConsumer(kafkaConsumer, reservationUseCases, productUseCases)
	reservationConsumer.StartAsync()

	sweepCtx, cancelSweep := context.WithCancel(context.Background())
	scheduled.NewHTTPRequestSweep(httpRequestStore).StartAsync(sweepCtx)
	scheduled.NewProcessedKafkaMessageSweep(processedMessageStore).StartAsync(sweepCtx)

	authCtx, cancelAuth := context.WithCancel(context.Background())
	tokenVerifier, err := jwks.NewJWKSTokenVerifier(authCtx, config.OIDCJWKSURI, config.OIDCIssuer, config.OIDCRolesClaim)
	if err != nil {
		slog.Error("failed to create access token verifier", "error", err)
		cancelAuth()
		cancelSweep()
		_ = reservationConsumer.Close()
		closeDB(db)
		os.Exit(1)
	}

	// The order matters - see error_handler_middleware.go.
	app := fiber.New(fiber.Config{
		ErrorHandler: middleware.HandleMiddlewareError,
		// Fiber's startup banner is plain text on stdout, between the JSON lines
		DisableStartupMessage: true,
	})
	// before any middleware: the probes are polled every few seconds, need no token, and are neither traced, nor
	// logged, nor counted
	healthController := inhttp.NewHealthController(sqlDB)
	healthController.RegisterRoutes(app)

	// outermost: the duration of every request, by its final status
	app.Use(middleware.MetricsMiddleware)
	// a server span per request, continuing the caller's trace, around everything below
	app.Use(otelfiber.Middleware(
		otelfiber.WithoutMetrics(true),
		otelfiber.WithSpanNameFormatter(func(c *fiber.Ctx) string { return c.Method() + " " + c.Route().Path }),
	))
	app.Use(middleware.Recover)
	app.Use(middleware.LoggingMiddleware)
	// before idempotency: a rejected request must not claim its Idempotency-Key
	app.Use(middleware.NewAuthMiddleware(tokenVerifier).Handle)
	app.Use(middleware.NewIdempotencyMiddleware(idempotencyService).Handle)
	// last, right before the routes: a controller's error must be the response by the time it gets
	// back to the idempotency middleware
	app.Use(middleware.HandleControllerError)

	apiV1 := app.Group("/api/v1")
	inhttp.NewProductController(productUseCases).RegisterRoutes(apiV1)
	inhttp.NewReservationController(reservationUseCases).RegisterRoutes(apiV1)

	go func() {
		if err = app.Listen(":" + config.Port); err != nil {
			slog.Error("failed to start http server", "error", err)
			os.Exit(1)
		}
	}()

	// migrations, topics, the consumer and the HTTP server are all up
	healthController.SetReady(true)

	quit := make(chan os.Signal, 1)
	signal.Notify(quit, syscall.SIGINT, syscall.SIGTERM)
	<-quit

	// first: the replica stops getting new requests before anything it needs for them is closed
	healthController.SetReady(false)

	if err = reservationConsumer.Close(); err != nil {
		slog.Error("error closing kafka consumer", "error", err)
	}

	cancelSweep()
	cancelAuth()

	if err = app.Shutdown(); err != nil {
		slog.Error("failed to shut down http server", "error", err)
		os.Exit(1)
	}

	closeDB(db)

	// last: everything that creates spans and records metrics has stopped, so this flushes all of them
	telemetryCtx, cancelTelemetry := context.WithTimeout(context.Background(), telemetryShutdownTimeout)
	defer cancelTelemetry()
	if err = shutdownTracing(telemetryCtx); err != nil {
		slog.Error("failed to flush traces", "error", err)
	}
	if err = shutdownMetrics(telemetryCtx); err != nil {
		slog.Error("failed to flush metrics", "error", err)
	}

	slog.Info("application shut down successfully")
}

// closeDB closes db's underlying connection pool, logging a failure instead of returning it -
// callers use this during shutdown or on a startup failure, when there's nothing left to do with
// the error but report it.
func closeDB(db *gorm.DB) {
	sqlDB, err := db.DB()
	if err != nil {
		return
	}

	if err = sqlDB.Close(); err != nil {
		slog.Error("error closing database connection", "error", err)
	}
}
