package model

// CancellationReason tells why a shipment was cancelled. Only a cancelled shipment has one.
type CancellationReason string

const (
	// NoCancellationReason is the reason of a shipment that isn't cancelled.
	NoCancellationReason CancellationReason = ""
	// OrderCancelled - the order was cancelled, so there is nothing to deliver anymore.
	OrderCancelled CancellationReason = "OrderCancelled"
	// RecipientRefused - the recipient refused the goods when the courier brought them.
	RecipientRefused CancellationReason = "RecipientRefused"
	// RecipientUnavailable - the courier couldn't hand the goods over: nobody was there, or the address was wrong.
	RecipientUnavailable CancellationReason = "RecipientUnavailable"
)

func (r CancellationReason) isValid() bool {
	switch r {
	case OrderCancelled, RecipientRefused, RecipientUnavailable:
		return true
	default:
		return false
	}
}

// IsReportedByCourier tells the reasons a courier finds out at the recipient's door from the one that reaches the
// shipment from outside, with the cancelled order.
func (r CancellationReason) IsReportedByCourier() bool {
	return r == RecipientRefused || r == RecipientUnavailable
}
