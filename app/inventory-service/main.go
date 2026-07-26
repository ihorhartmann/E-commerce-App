package main

import (
	"inventoryservice/application/port/common/kafka"
	"inventoryservice/interfaces/http/middleware"
	"inventoryservice/interfaces/kafka/kafkago"
	"log/slog"
	"net/http"
	"os"
	"os/signal"
	"syscall"

	"inventoryservice/application"
	"inventoryservice/infrastructure/env"
	"inventoryservice/infrastructure/repository"
	inhttp "inventoryservice/interfaces/http"
	inkafka "inventoryservice/interfaces/kafka"

	"github.com/gofiber/fiber/v2"
)

func main() {
	slog.SetDefault(slog.New(slog.NewTextHandler(os.Stdout, &slog.HandlerOptions{Level: slog.LevelDebug})))

	config, err := env.LoadFromEnv()
	if err != nil {
		slog.Error("failed to load configuration", "error", err)
		os.Exit(1)
	}

	productRepository := repository.NewInMemoryProductRepository()
	productUseCases := application.NewProductService(productRepository)

	reservationRepository := repository.NewInMemoryReservationRepository()
	reservationUseCases := application.NewReservationService(reservationRepository, productRepository)

	brokers := []string{config.KafkaHost + ":" + config.KafkaPort}
	kafkaConsumer := kafkago.NewKafkaGoMessageConsumer(brokers, kafka.InventoryCommandsTopic, kafka.ConsumerGroupID)
	reservationConsumer := inkafka.NewReservationKafkaConsumer(kafkaConsumer, reservationUseCases, productUseCases)
	reservationConsumer.Start()

	app := fiber.New(fiber.Config{
		ErrorHandler: middleware.HandleError,
	})
	app.Use(middleware.LoggingMiddleware)

	app.Get("/health", func(c *fiber.Ctx) error {
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

	if err = app.Shutdown(); err != nil {
		slog.Error("failed to shut down http server", "error", err)
		os.Exit(1)
	}
	slog.Info("application shut down successfully")
}
