package http

import (
	dto "inventoryservice/interfaceadapters/in/http/dto/product"
	"inventoryservice/interfaceadapters/in/http/errors"
	"inventoryservice/usecases"
	"net/http"

	"github.com/gofiber/fiber/v2"
)

type ProductController struct {
	productUseCases *usecases.ProductUseCases
}

func NewProductController(productUseCases *usecases.ProductUseCases) *ProductController {
	return &ProductController{productUseCases: productUseCases}
}

func (pc *ProductController) RegisterRoutes(router fiber.Router) {
	pg := router.Group("/products")

	pg.Post("/", pc.CreateProduct)
	pg.Put("/:id", pc.UpdateProduct)
	pg.Get("/:id", pc.GetProduct)
	pg.Delete("/:id", pc.DeleteProduct)
}

func (pc *ProductController) CreateProduct(c *fiber.Ctx) error {
	var request dto.CreateProductRequest
	if err := c.BodyParser(&request); err != nil {
		return errors.HandleProductError(errors.ErrInvalidRequestBody)
	}

	product, err := dto.FromCreateProductRequest(request)
	if err != nil {
		return errors.HandleProductError(err)
	}

	createdProduct, err := pc.productUseCases.Create(c.Context(), product)
	if err != nil {
		return errors.HandleProductError(err)
	}

	return c.Status(http.StatusCreated).JSON(dto.ToProductResponse(createdProduct))
}

func (pc *ProductController) UpdateProduct(c *fiber.Ctx) error {
	id, err := parseIDParam(c, "id")
	if err != nil {
		return errors.HandleProductError(errors.ErrInvalidProductID)
	}

	var request dto.UpdateProductRequest
	if err = c.BodyParser(&request); err != nil {
		return errors.HandleProductError(errors.ErrInvalidRequestBody)
	}

	product, err := dto.FromUpdateProductRequest(id, request)
	if err != nil {
		return errors.HandleProductError(err)
	}

	updatedProduct, err := pc.productUseCases.Update(c.Context(), product)
	if err != nil {
		return errors.HandleProductError(err)
	}

	return c.JSON(dto.ToProductResponse(updatedProduct))
}

func (pc *ProductController) GetProduct(c *fiber.Ctx) error {
	id, err := parseIDParam(c, "id")
	if err != nil {
		return errors.HandleProductError(errors.ErrInvalidProductID)
	}

	product, err := pc.productUseCases.Get(c.Context(), id)
	if err != nil {
		return errors.HandleProductError(err)
	}

	return c.JSON(dto.ToProductResponse(product))
}

func (pc *ProductController) DeleteProduct(c *fiber.Ctx) error {
	id, err := parseIDParam(c, "id")
	if err != nil {
		return errors.HandleProductError(errors.ErrInvalidProductID)
	}

	if err = pc.productUseCases.Delete(c.Context(), id); err != nil {
		return errors.HandleProductError(err)
	}

	return c.SendStatus(http.StatusNoContent)
}
