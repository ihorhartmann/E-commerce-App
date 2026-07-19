package kafka

type EventType string

func (et EventType) String() string {
	return string(et)
}

const (
	CancelReservationEventType  EventType = "CancelReservation"
	FulfillReservationEventType EventType = "FulfillReservation"
	HoldReservationEventType    EventType = "HoldReservation"
	RestockEventType            EventType = "Restock"
)
