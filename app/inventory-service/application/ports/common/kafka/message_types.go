package kafka

type MessageType string

func (mt MessageType) String() string {
	return string(mt)
}

const (
	CancelReservationCommand  MessageType = "CancelReservation"
	FulfillReservationCommand MessageType = "FulfillReservation"
	HoldReservationCommand    MessageType = "HoldReservation"
	RestockCommand            MessageType = "Restock"
)
