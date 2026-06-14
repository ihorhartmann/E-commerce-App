package http

import (
	"inventoryservice/interfaceadapters/in/http/dto"
	"inventoryservice/usecases"

	"github.com/gofiber/fiber/v2"
)

type ProductController struct {
	productUseCase *usecases.ProductUseCase
}

func NewProductController(productUseCase *usecases.ProductUseCase) *ProductController {
	return &ProductController{productUseCase: productUseCase}
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
		return c.Status(fiber.StatusBadRequest).JSON(errorResponse(ErrInvalidRequestBody))
	}

	product, err := request.ToDomainModel()
	if err != nil {
		return handleError(c, err)
	}

	createdProduct, err := pc.productUseCase.Create(c.Context(), product)
	if err != nil {
		return handleError(c, err)
	}

	return c.Status(fiber.StatusCreated).JSON(dto.FromDomainModel(createdProduct))
}

func (pc *ProductController) UpdateProduct(c *fiber.Ctx) error {
	id, err := parseIDParam(c, "id")
	if err != nil {
		return c.Status(fiber.StatusBadRequest).JSON(errorResponse(ErrInvalidProductID))
	}

	var request dto.UpdateProductRequest
	if err = c.BodyParser(&request); err != nil {
		return c.Status(fiber.StatusBadRequest).JSON(errorResponse(ErrInvalidRequestBody))
	}

	product, err := request.ToDomainModel(id)
	if err != nil {
		return handleError(c, err)
	}

	updatedProduct, err := pc.productUseCase.Update(c.Context(), product)
	if err != nil {
		return handleError(c, err)
	}

	return c.JSON(dto.FromDomainModel(updatedProduct))
}

func (pc *ProductController) GetProduct(c *fiber.Ctx) error {
	id, err := parseIDParam(c, "id")
	if err != nil {
		return c.Status(fiber.StatusBadRequest).JSON(errorResponse(ErrInvalidProductID))
	}

	product, err := pc.productUseCase.Get(c.Context(), id)
	if err != nil {
		return handleError(c, err)
	}

	return c.JSON(dto.FromDomainModel(product))
}

func (pc *ProductController) DeleteProduct(c *fiber.Ctx) error {
	id, err := parseIDParam(c, "id")
	if err != nil {
		return c.Status(fiber.StatusBadRequest).JSON(errorResponse(ErrInvalidProductID))
	}

	if err = pc.productUseCase.Delete(c.Context(), id); err != nil {
		return handleError(c, err)
	}

	return c.SendStatus(fiber.StatusNoContent)
}
