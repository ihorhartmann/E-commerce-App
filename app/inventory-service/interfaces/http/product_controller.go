package http

import (
	"inventoryservice/application/port/in"
	dto "inventoryservice/interfaces/http/dto/product"
	"inventoryservice/interfaces/http/errors"
	"net/http"

	"github.com/gofiber/fiber/v2"
)

// defaultPageSize is the page size of a list request that doesn't ask for one.
const defaultPageSize = 20

type ProductController struct {
	productService in.ProductService
}

func NewProductController(productService in.ProductService) *ProductController {
	return &ProductController{productService: productService}
}

func (pc *ProductController) RegisterRoutes(router fiber.Router) {
	pg := router.Group("/products")

	pg.Get("/", pc.GetProducts)
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

	createdProduct, err := pc.productService.Create(c.Context(), product)
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

	update, err := dto.FromUpdateProductRequest(id, request)
	if err != nil {
		return errors.HandleProductError(err)
	}

	updatedProduct, err := pc.productService.Update(c.Context(), update)
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

	product, err := pc.productService.Get(c.Context(), id)
	if err != nil {
		return errors.HandleProductError(err)
	}

	return c.JSON(dto.ToProductResponse(product))
}

func (pc *ProductController) GetProducts(c *fiber.Ctx) error {
	page, err := parseIntQuery(c, "page", 0)
	if err != nil {
		return errors.HandleProductError(errors.ErrInvalidPageParam)
	}
	size, err := parseIntQuery(c, "size", defaultPageSize)
	if err != nil {
		return errors.HandleProductError(errors.ErrInvalidPageParam)
	}

	productPage, err := pc.productService.GetProducts(c.Context(), page, size)
	if err != nil {
		return errors.HandleProductError(err)
	}

	return c.JSON(dto.ToProductListResponse(productPage, page, size))
}

func (pc *ProductController) DeleteProduct(c *fiber.Ctx) error {
	id, err := parseIDParam(c, "id")
	if err != nil {
		return errors.HandleProductError(errors.ErrInvalidProductID)
	}

	if err = pc.productService.Delete(c.Context(), id); err != nil {
		return errors.HandleProductError(err)
	}

	return c.SendStatus(http.StatusNoContent)
}
