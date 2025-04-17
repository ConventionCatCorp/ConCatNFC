package types

type Response struct {
	UUID  string `json:"uuid,omitempty"`
	Error string `json:"error,omitempty"`
}
