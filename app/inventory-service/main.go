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
	"net/http"
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
	healthPath = "/health"
	// how long shutdown waits for the buffered spans to reach the collector
	tracingShutdownTimeout = 5 * time.Second
)

func main() {
	slog.SetDefault(slog.New(slog.NewTextHandler(os.Stdout, &slog.HandlerOptions{Level: slog.LevelDebug})))

	config, err := env.LoadFromEnv()
	if err != nil {
		slog.Error("failed to load configuration", "error", err)
		os.Exit(1)
	}

	// first, so the database, Kafka and HTTP instrumentation below all find the provider installed
	shutdownTracing, err := telemetry.InitTracing(context.Background(), config.OTELExporterOTLPEndpoint, config.OTELServiceName)
	if err != nil {
		slog.Error("failed to set up tracing", "error", err)
		os.Exit(1)
	}

	db, err := gormconn.NewDB(config)
	if err != nil {
		slog.Error("failed to connect to database", "error", err)
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
	})
	// outermost: a server span per request, continuing the caller's trace, around everything below - health checks
	// excepted, polled every few seconds
	app.Use(otelfiber.Middleware(otelfiber.WithoutMetrics(true), otelfiber.WithNext(func(c *fiber.Ctx) bool {
		return c.Path() == healthPath
	})))
	app.Use(middleware.Recover)
	app.Use(middleware.LoggingMiddleware)
	// before idempotency: a rejected request must not claim its Idempotency-Key
	app.Use(middleware.NewAuthMiddleware(tokenVerifier).Handle)
	app.Use(middleware.NewIdempotencyMiddleware(idempotencyService).Handle)
	// last, right before the routes: a controller's error must be the response by the time it gets
	// back to the idempotency middleware
	app.Use(middleware.HandleControllerError)

	app.Get(healthPath, func(c *fiber.Ctx) error {
		return c.Status(http.StatusOK).JSON(fiber.Map{"status": "ok"})
	})

	apiV1 := app.Group("/api/v1")
	inhttp.NewProductController(productUseCases).RegisterRoutes(apiV1)
	inhttp.NewReservationController(reservationUseCases).RegisterRoutes(apiV1)

	go func() {
		if err = app.Listen(":" + config.Port); err != nil {
			slog.Error("failed to start http server", "error", err)
			os.Exit(1)
		}
	}()

	quit := make(chan os.Signal, 1)
	signal.Notify(quit, syscall.SIGINT, syscall.SIGTERM)
	<-quit

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

	// last: everything that creates spans has stopped, so this flushes all of them
	tracingCtx, cancelTracing := context.WithTimeout(context.Background(), tracingShutdownTimeout)
	defer cancelTracing()
	if err = shutdownTracing(tracingCtx); err != nil {
		slog.Error("failed to flush traces", "error", err)
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
