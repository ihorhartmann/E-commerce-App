package product

import (
	"inventoryservice/application/dto"
	"inventoryservice/domain/model"
)

type ListProductsResponse struct {
	Items         []CatalogProductResponse `json:"items"`
	Page          int                      `json:"page"`
	Size          int                      `json:"size"`
	TotalElements int64                    `json:"totalElements"`
	TotalPages    int64                    `json:"totalPages"`
}

type CatalogProductResponse struct {
	ID          uint64   `json:"id"`
	Name        string   `json:"name"`
	Description string   `json:"description"`
	Price       MoneyDTO `json:"price"`
	Available   uint     `json:"available"`
}

func ToProductListResponse(productPage *dto.ProductPageDTO, page, size int) *ListProductsResponse {
	items := make([]CatalogProductResponse, 0, len(productPage.Products))
	for _, product := range productPage.Products {
		items = append(items, toCatalogProductResponse(product))
	}

	return &ListProductsResponse{
		Items:         items,
		Page:          page,
		Size:          size,
		TotalElements: productPage.TotalElements,
		TotalPages:    (productPage.TotalElements + int64(size) - 1) / int64(size),
	}
}

func toCatalogProductResponse(product *model.Product) CatalogProductResponse {
	return CatalogProductResponse{
		ID:          product.ID(),
		Name:        product.Name(),
		Description: product.Description(),
		Price:       ToMoneyDTO(product.Price()),
		Available:   product.Available(),
	}
}
