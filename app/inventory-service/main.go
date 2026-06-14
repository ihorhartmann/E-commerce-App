package main

import (
	"inventoryservice/env"
	"inventoryservice/interfaceadapters/in/http"
	"inventoryservice/interfaceadapters/out/repository"
	"inventoryservice/usecases"
	"log"
	"os"
	"os/signal"
	"syscall"

	"github.com/gofiber/fiber/v2"
)

func main() {
	config, err := env.LoadFromEnv()
	if err != nil {
		panic(err)
	}

	productRepository := repository.NewInMemoryProductRepository()
	productUseCase := usecases.NewProductUseCase(productRepository)

	app := fiber.New()
	apiV1 := app.Group("/api/v1")
	http.NewProductController(productUseCase).RegisterRoutes(apiV1)

	go func() {
		if err = app.Listen(":" + config.Port); err != nil {
			log.Fatal(err)
		}
	}()

	quit := make(chan os.Signal, 1)
	signal.Notify(quit, syscall.SIGINT, syscall.SIGTERM)
	<-quit

	if err = app.Shutdown(); err != nil {
		log.Fatal(err)
	}
	log.Println("application shut down successfully")
}
