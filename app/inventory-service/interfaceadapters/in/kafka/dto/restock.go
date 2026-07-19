package dto

type RestockItem struct {
	ProductID uint64 `json:"productId"`
	Quantity  uint   `json:"quantity"`
}

type RestockMessage struct {
	OrderID uint64        `json:"orderId"`
	Items   []RestockItem `json:"items"`
}
