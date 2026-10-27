package http

import (
	"inventoryservice/application/port/in"
	dto "inventoryservice/interfaces/http/dto/product"
	"inventoryservice/interfaces/http/httperror"
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
		return httperror.ErrMalformedRequestBody.WithInternalMessage("parse product request body").WithCause(err)
	}

	product, err := dto.FromCreateProductRequest(request)
	if err != nil {
		return err
	}

	createdProduct, err := pc.productService.Create(c.UserContext(), product)
	if err != nil {
		return err
	}

	return c.Status(http.StatusCreated).JSON(dto.ToProductResponse(createdProduct))
}

func (pc *ProductController) UpdateProduct(c *fiber.Ctx) error {
	id, err := parseIDParam(c, "id")
	if err != nil {
		return err
	}

	var request dto.UpdateProductRequest
	if err = c.BodyParser(&request); err != nil {
		return httperror.ErrMalformedRequestBody.WithInternalMessage("parse product request body").WithCause(err)
	}

	update, err := dto.FromUpdateProductRequest(id, request)
	if err != nil {
		return err
	}

	updatedProduct, err := pc.productService.Update(c.UserContext(), update)
	if err != nil {
		return err
	}

	return c.JSON(dto.ToProductResponse(updatedProduct))
}

func (pc *ProductController) GetProduct(c *fiber.Ctx) error {
	id, err := parseIDParam(c, "id")
	if err != nil {
		return err
	}

	product, err := pc.productService.Get(c.UserContext(), id)
	if err != nil {
		return err
	}

	return c.JSON(dto.ToProductResponse(product))
}

func (pc *ProductController) GetProducts(c *fiber.Ctx) error {
	page, err := parseIntQuery(c, "page", 0)
	if err != nil {
		return err
	}
	size, err := parseIntQuery(c, "size", defaultPageSize)
	if err != nil {
		return err
	}

	productPage, err := pc.productService.GetProducts(c.UserContext(), page, size)
	if err != nil {
		return err
	}

	return c.JSON(dto.ToProductListResponse(productPage, page, size))
}

func (pc *ProductController) DeleteProduct(c *fiber.Ctx) error {
	id, err := parseIDParam(c, "id")
	if err != nil {
		return err
	}

	if err = pc.productService.Delete(c.UserContext(), id); err != nil {
		return err
	}

	return c.SendStatus(http.StatusNoContent)
}
