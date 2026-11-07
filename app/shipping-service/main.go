package main

import (
	"context"
	"errors"
	"log/slog"
	"net/http"
	"os"
	"os/signal"
	kafkaconst "shippingservice/adapter/common/kafka"
	"shippingservice/adapter/in/http/middleware"
	"shippingservice/adapter/out/idempotency/store"
	"syscall"
	"time"

	shippinghttp "shippingservice/adapter/in/http"
	"shippingservice/adapter/in/http/httperror"
	httpidempotency "shippingservice/adapter/in/http/idempotency"
	"shippingservice/adapter/in/kafka"
	"shippingservice/adapter/in/scheduled"
	"shippingservice/adapter/out/outbox"
	"shippingservice/adapter/out/repository"
	gormtx "shippingservice/adapter/out/repository/transaction"
	"shippingservice/application/service"
	"shippingservice/infrastructure/env"
	gormconn "shippingservice/infrastructure/gorm"
	"shippingservice/infrastructure/jwks"
	"shippingservice/infrastructure/kafkago"
	"shippingservice/infrastructure/telemetry"

	"github.com/gin-gonic/gin"
	"go.opentelemetry.io/contrib/instrumentation/github.com/gin-gonic/gin/otelgin"
	"go.opentelemetry.io/otel/metric/noop"
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

	shipmentRepository := repository.NewPostgresShipmentRepository(db)
	outboxRepository := outbox.NewPostgresOutboxMessageRepository(db)
	txExecutor := gormtx.NewGormTxManager(db)
	processedMessageStore := store.NewPostgresProcessedKafkaMessageStore(db)
	httpRequestStore := store.NewPostgresHTTPRequestStore(db)
	idempotencyService := httpidempotency.NewHTTPIdempotencyService(httpRequestStore)

	brokers := []string{config.KafkaHost + ":" + config.KafkaPort}
	if err = kafkago.CreateTopics(context.Background(), brokers); err != nil {
		slog.Error("failed to declare kafka topics", "error", err)
		closeDB(db)
		os.Exit(1)
	}

	messageProducer := kafkago.NewKafkaGoMessageProducer(brokers, kafkaconst.ShippingEventsTopic)
	shipmentProducer := outbox.NewOutboxShipmentKafkaProducer(outboxRepository)

	shipmentService := service.NewShipmentServiceImpl(shipmentRepository, shipmentProducer, processedMessageStore, txExecutor)

	messageConsumer := kafkago.NewKafkaGoMessageConsumer(brokers, kafkaconst.ShippingCommandsTopic, kafkaconst.ConsumerGroupID)
	shipmentConsumer := kafka.NewShipmentKafkaConsumer(messageConsumer, shipmentService)
	shipmentConsumer.Start()

	sweepCtx, cancelSweep := context.WithCancel(context.Background())
	scheduled.NewHTTPRequestSweep(httpRequestStore).StartAsync(sweepCtx)
	scheduled.NewProcessedKafkaMessageSweep(processedMessageStore).StartAsync(sweepCtx)
	scheduled.NewOutboxMessageRelay(outboxRepository, messageProducer).StartAsync(sweepCtx)

	authCtx, cancelAuth := context.WithCancel(context.Background())
	tokenVerifier, err := jwks.NewJWKSTokenVerifier(authCtx, config.OIDCJWKSURI, config.OIDCIssuer, config.OIDCRolesClaim)
	if err != nil {
		slog.Error("failed to create access token verifier", "error", err)
		cancelAuth()
		cancelSweep()
		_ = shipmentConsumer.Close()
		_ = messageProducer.Close()
		closeDB(db)
		os.Exit(1)
	}

	// release mode: in debug mode Gin writes its route table and warnings to stdout as plain text, between the JSON lines
	gin.SetMode(gin.ReleaseMode)

	// The order matters - see error_handler_middleware.go. gin.New, not gin.Default: Default adds its
	// own logger and its own panic recovery, which would write a plain 500 instead of a ProblemDetail.
	router := gin.New()
	router.HandleMethodNotAllowed = true
	// before any middleware: the probes are polled every few seconds, need no token, and are neither traced, nor
	// logged, nor counted
	healthController := shippinghttp.NewHealthController(sqlDB)
	healthController.RegisterRoutes(router)

	router.Use(
		// outermost: the duration of every request, by its final status
		middleware.MetricsMiddleware,
		// a server span per request, continuing the caller's trace, around everything below; its own metrics are
		// left out - MetricsMiddleware records the same as the other services
		otelgin.Middleware(config.OTELServiceName, otelgin.WithMeterProvider(noop.NewMeterProvider())),
		middleware.HandleMiddlewareError,
		middleware.Recover,
		middleware.LoggingMiddleware,
		// before idempotency: a rejected request must not claim its Idempotency-Key
		middleware.NewAuthMiddleware(tokenVerifier).Handle,
		middleware.NewIdempotencyMiddleware(idempotencyService).Handle,
		// last, right before the routes: a controller's error must be the response by the time it gets
		// back to the idempotency middleware
		middleware.HandleControllerError,
	)
	router.NoRoute(func(c *gin.Context) { _ = c.Error(httperror.ErrRouteNotFound) })
	router.NoMethod(func(c *gin.Context) { _ = c.Error(httperror.ErrMethodNotAllowed) })

	apiV1 := router.Group("/api/v1")
	shippinghttp.NewShipmentController(shipmentService).RegisterRoutes(apiV1)

	server := &http.Server{
		Addr:    ":" + config.Port,
		Handler: router,
	}

	go func() {
		if err = server.ListenAndServe(); err != nil && !errors.Is(err, http.ErrServerClosed) {
			slog.Error("failed to start http server", "error", err)
			os.Exit(1)
		}
	}()

	// migrations, topics, the consumer, the relay and the HTTP server are all up
	healthController.SetReady(true)

	quit := make(chan os.Signal, 1)
	signal.Notify(quit, syscall.SIGINT, syscall.SIGTERM)
	<-quit

	// first: the replica stops getting new requests before anything it needs for them is closed
	healthController.SetReady(false)

	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()

	if err = server.Shutdown(ctx); err != nil {
		slog.Error("failed to shut down http server", "error", err)
		os.Exit(1)
	}
	if err = shipmentConsumer.Close(); err != nil {
		slog.Error("error closing kafka consumer", "error", err)
	}
	cancelSweep()
	cancelAuth()
	if err = messageProducer.Close(); err != nil {
		slog.Error("error closing kafka producer", "error", err)
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

func closeDB(db *gorm.DB) {
	sqlDB, err := db.DB()
	if err != nil {
		return
	}

	if err = sqlDB.Close(); err != nil {
		slog.Error("error closing database connection", "error", err)
	}
}
