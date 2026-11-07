package dto

// HealthResponse is the body of every health endpoint - the same as Spring Boot Actuator's, without its details.
type HealthResponse struct {
	Status string `json:"status"`
}
