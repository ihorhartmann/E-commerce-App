package dto

type RequestFingerprint struct {
	Method string
	Path   string
	Body   []byte
}
