package dto

import "inventoryservice/domain/model"

// ProductPageDTO is one page of products, with the number of products across all pages.
type ProductPageDTO struct {
	Products      []*model.Product
	TotalElements int64
}
