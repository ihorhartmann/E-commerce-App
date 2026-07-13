package main

import (
	"context"
	"errors"
	"log"
	"net/http"
	"os"
	"os/signal"
	shippinghttp "shippingservice/adapter/in/http"
	"shippingservice/adapter/in/kafka"
	outkafka "shippingservice/adapter/out/kafka"
	"shippingservice/adapter/out/repository"
	"shippingservice/application/service"
	"shippingservice/infrastructure/env"
	"shippingservice/infrastructure/kafkago"
	"syscall"
	"time"

	"github.com/gin-gonic/gin"
)

func main() {
	config, err := env.LoadFromEnv()
	if err != nil {
		panic(err)
	}

	shipmentRepository := repository.NewInMemoryShipmentRepository()
	shipmentService := service.NewShipmentServiceImpl(shipmentRepository)

	messageConsumer := kafkago.NewStubMessageConsumer()
	shipmentConsumer := kafka.NewShipmentConsumer(messageConsumer)
	shipmentConsumer.Start()

	messageProducer := kafkago.NewStubMessageProducer()
	shipmentProducer := outkafka.NewKafkaGoShipmentProducer(messageProducer)

	router := gin.Default()
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
		if err = server.ListenAndServe(); err != nil && !errors.Is(http.ErrServerClosed, err) {
			log.Fatal(err)
		}
	}()

	quit := make(chan os.Signal, 1)
	signal.Notify(quit, syscall.SIGINT, syscall.SIGTERM)
	<-quit

	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()

	if err = server.Shutdown(ctx); err != nil {
		log.Fatal(err)
	}
	if err = shipmentConsumer.Close(); err != nil {
		log.Fatal(err)
	}
	if err = shipmentProducer.Close(); err != nil {
		log.Fatal(err)
	}

	log.Println("application shut down successfully")
}
