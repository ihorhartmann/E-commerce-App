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

	"github.com/gin-gonic/gin"
	"gorm.io/gorm"
)

func main() {
	slog.SetDefault(slog.New(slog.NewTextHandler(os.Stdout, &slog.HandlerOptions{Level: slog.LevelDebug})))

	config, err := env.LoadFromEnv()
	if err != nil {
		slog.Error("failed to load configuration", "error", err)
		os.Exit(1)
	}

	db, err := gormconn.NewDB(config)
	if err != nil {
		slog.Error("failed to connect to database", "error", err)
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

	router := gin.Default()
	router.Use(
		middleware.LoggingMiddleware(),
		middleware.ErrorHandlerMiddleware(),
		// before idempotency: a rejected request must not claim its Idempotency-Key
		middleware.NewAuthMiddleware(tokenVerifier).Handle,
		middleware.NewIdempotencyMiddleware(idempotencyService).Handle,
	)

	router.GET("/health", func(c *gin.Context) {
		c.JSON(http.StatusOK, gin.H{"status": "ok"})
	})

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

	quit := make(chan os.Signal, 1)
	signal.Notify(quit, syscall.SIGINT, syscall.SIGTERM)
	<-quit

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
