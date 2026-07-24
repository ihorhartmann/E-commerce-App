package dto

type RestockItem struct {
	ProductID uint64 `json:"productId"`
	Quantity  uint   `json:"quantity"`
}

type RestockCommand struct {
	OrderID uint64        `json:"orderId"`
	Items   []RestockItem `json:"items"`
}
