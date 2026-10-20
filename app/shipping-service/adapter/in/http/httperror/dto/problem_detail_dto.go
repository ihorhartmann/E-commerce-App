package dto

// ProblemDetail is the body of every error response, per RFC 9457, with the extension members
// code and retryable.
type ProblemDetail struct {
	Type      string `json:"type"`
	Title     string `json:"title"`
	Status    int    `json:"status"`
	Detail    string `json:"detail,omitempty"`
	Instance  string `json:"instance"`
	Code      string `json:"code"`
	Retryable bool   `json:"retryable"`
}
