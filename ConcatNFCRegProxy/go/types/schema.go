package types

type Response struct {
	UUID  string `json:"uuid,omitempty"`
	Error string `json:"error,omitempty"`
}

type Tag struct {
	Id   byte
	Data []byte
}

type CardDefinitionRequest struct {
	AttendeeId   uint32 `json:"attendee_id,omitempty"`
	ConventionId uint32 `json:"convention_id,omitempty"`
	Issuance     uint32 `json:"issuance,omitempty"`
	Timestamp    uint64 `json:"timestamp,omitempty"`
	Expiration   uint64 `json:"expiration,omitempty"`
	Signature    string `json:"signature,omitempty"`
	Password     uint64 `json:"password,omitempty"`
	UUID         string `json:"uuid,omitempty"`
}
