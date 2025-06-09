package types

type Response struct {
	UUID    string `json:"uuid,omitempty"`
	Error   string `json:"error,omitempty"`
	Success bool   `json:"success"`
	//Use pointer because if we use a empty object the response will always contain an empty card object
	Card *CardDefinitionRequest `json:"card,omitempty"`
}

type Tag struct {
	Id   byte
	Data []byte
}

type CardDefinitionRequest struct {
	AttendeeId        uint32 `json:"attendee_id,omitempty"`
	ConventionId      uint32 `json:"convention_id,omitempty"`
	IssuanceCount     uint32 `json:"issuance,omitempty"`
	IssuanceTimestamp uint64 `json:"timestamp,omitempty"`
	Expiration        uint64 `json:"expiration,omitempty"`
	Signature         string `json:"signature,omitempty"`
	Password          uint32 `json:"password,omitempty"`
	UUID              string `json:"uuid,omitempty"`
}

type CardReadSetPasswordRequest struct {
	Password uint32 `json:"password,omitempty"`
	UUID     string `json:"uuid,omitempty"`
}
