package main

import (
	"bytes"
	"encoding/json"
	"errors"
	"net/http"
	"net/http/httptest"
	"sync"
	"testing"
	"time"

	"ConcatNFCRegProxy/types"

	"github.com/gin-gonic/gin"
	"github.com/stretchr/testify/assert"
)

type MockNFC struct {
	AuthRequired   bool
	Locked         bool
	Password       uint32
	StoredTags     []types.Tag
	ConnectionLock sync.Mutex
}

var CARD_UUID string = "04412a014b3403"

func (m *MockNFC) IsReady() bool            { return true }
func (m *MockNFC) GetUUID() (string, error) { return CARD_UUID, nil }

func (m *MockNFC) SetNTAG21xPassword(password uint32) error {
	m.Password = password
	m.AuthRequired = true
	return nil
}

func (m *MockNFC) IsAuthRequired() bool { return m.AuthRequired }

func (m *MockNFC) NTAG21xAuth(password uint32) error {
	if !m.AuthRequired {
		return nil
	}
	if password != m.Password {
		return errors.New("invalid password")
	}
	return nil
}

func (m *MockNFC) WriteTags(tags []types.Tag) error {
	m.StoredTags = append([]types.Tag{}, tags...) // Copy to avoid reference issues
	return nil
}
func (m *MockNFC) ReadTags() ([]types.Tag, error) {
	return append([]types.Tag{}, m.StoredTags...), nil
}

func (m *MockNFC) Lock() {
	m.Locked = true
}

func (m *MockNFC) BeepReader() error {
	return nil
}

func (m *MockNFC) Unlock() {
	m.Locked = false
}

func (m *MockNFC) ClearNTAG21xPassword() error {
	m.Password = 0
	m.AuthRequired = false
	return nil
}

func setupMock() *gin.Engine {
	h := &HandlerContext{env: &MockNFC{}}
	r := gin.Default()
	r.Use(CORSMiddleware())
	r.GET("/healthcheck", h.healthcheck)
	r.GET("/uuid", h.getUUID)
	r.POST("/write", h.writeData)
	r.PATCH("/write", h.updateData)
	r.PUT("/read", h.readData)
	r.PUT("/setpassword", h.setPassword)
	r.PUT("/clearpassword", h.clearPassword)
	r.GET("/events", h.sseHandler)
	return r
}

func TestCardReadEmpty(t *testing.T) {

	r := setupMock()

	w := httptest.NewRecorder()
	body, _ := json.Marshal(types.CardDefinitionRequest{
		Password: 123,
		UUID:     CARD_UUID,
	})
	req, _ := http.NewRequest("PUT", "/read", bytes.NewBuffer(body))
	r.ServeHTTP(w, req)

	assert.Equal(t, 417, w.Code)
	assert.Contains(t, w.Body.String(), "Card is empty!")

}

func TestCardWrite(t *testing.T) {

	r := setupMock()

	nowIunix := uint64(time.Now().Unix())
	w := httptest.NewRecorder()
	body, _ := json.Marshal(types.CardDefinitionRequest{
		AttendeeId:        123,
		ConventionId:      32,
		IssuanceCount:     1,
		IssuanceTimestamp: nowIunix,
		Expiration:        uint64(nowIunix + uint64(3600*24)),
		Signature:         "MTIzNDU2Nzg5MDEyMzQ1Njc4OTAxMjM0NTY3ODkwMTIzNDU2Nzg5MDEyMzQ1Njc4OTAxMjM0NTY3ODkwMTIzNDU2Nzg5MDEyMzQ=",
		Password:          123,
		UUID:              CARD_UUID,
	})
	req, _ := http.NewRequest("POST", "/write", bytes.NewBuffer(body))
	r.ServeHTTP(w, req)

	assert.Equal(t, 200, w.Code)

	w2 := httptest.NewRecorder()
	body2, _ := json.Marshal(types.CardDefinitionRequest{
		Password: 123,
		UUID:     "hahahahaha",
	})
	req2, _ := http.NewRequest("PUT", "/read", bytes.NewBuffer(body2))
	r.ServeHTTP(w2, req2)
	assert.Equal(t, 403, w2.Code)

	w3 := httptest.NewRecorder()
	body3, _ := json.Marshal(types.CardDefinitionRequest{
		Password: 123,
		UUID:     CARD_UUID,
	})
	req3, _ := http.NewRequest("PUT", "/read", bytes.NewBuffer(body3))
	r.ServeHTTP(w3, req3)
	assert.Equal(t, 200, w3.Code)

	var res types.Response
	err := json.Unmarshal(w3.Body.Bytes(), &res)
	assert.NoError(t, err)

	assert.True(t, res.Success)
	assert.NotNil(t, res.Card)
	assert.Equal(t, uint32(123), res.Card.AttendeeId)
	assert.Equal(t, uint32(32), res.Card.ConventionId)
	assert.Equal(t, uint32(1), res.Card.IssuanceCount)
	assert.Equal(t, nowIunix+3600*24, res.Card.Expiration)
	assert.Equal(t, "MTIzNDU2Nzg5MDEyMzQ1Njc4OTAxMjM0NTY3ODkwMTIzNDU2Nzg5MDEyMzQ1Njc4OTAxMjM0NTY3ODkwMTIzNDU2Nzg5MDEyMzQ=", res.Card.Signature)
	//Should not return a password
	assert.Equal(t, uint32(0), res.Card.Password)

	body4, _ := json.Marshal(types.CardDefinitionRequest{
		ConventionId: 33,
		Password:     123,
		UUID:         CARD_UUID,
	})
	req4, _ := http.NewRequest("PATCH", "/write", bytes.NewBuffer(body4))

	w4 := httptest.NewRecorder()
	r.ServeHTTP(w4, req4)

	assert.Equal(t, 400, w4.Code)

	body5, _ := json.Marshal(types.CardDefinitionRequest{
		ConventionId:      33,
		Password:          123,
		AttendeeId:        124,
		IssuanceTimestamp: nowIunix + 3,
		Expiration:        uint64(nowIunix + uint64(3600*22)),
		Signature:         "MTIzNDU2Nzg5MDEyMzQ1Njc4OTAxMjM0NTY3ODkwMTIzNDU2Nzg5MDEyMzQ1Njc4OTAxMjM0NTY3ODkwMTIzNDU2Nzg5MDEyMzQ=",
		UUID:              CARD_UUID,
	})
	req5, _ := http.NewRequest("PATCH", "/write", bytes.NewBuffer(body5))
	w5 := httptest.NewRecorder()
	r.ServeHTTP(w5, req5)
	assert.Equal(t, 200, w5.Code)
	var res2 types.Response
	err2 := json.Unmarshal(w5.Body.Bytes(), &res2)
	assert.NoError(t, err2)

	assert.True(t, res2.Success)
	assert.Nil(t, res2.Card)

}

func TestCardPassword(t *testing.T) {

	r := setupMock()

	w := httptest.NewRecorder()
	body, _ := json.Marshal(types.CardDefinitionRequest{
		UUID: CARD_UUID,
	})
	req, _ := http.NewRequest("PUT", "/read", bytes.NewBuffer(body))
	r.ServeHTTP(w, req)

	assert.Equal(t, 417, w.Code)
	assert.Contains(t, w.Body.String(), "Card is empty!")

	nowIunix := uint64(time.Now().Unix())

	w2 := httptest.NewRecorder()
	body2, _ := json.Marshal(types.CardDefinitionRequest{
		AttendeeId:        123,
		ConventionId:      32,
		IssuanceCount:     1,
		IssuanceTimestamp: nowIunix,
		Expiration:        uint64(nowIunix + uint64(3600*24)),
		Password:          1,
		Signature:         "MTIzNDU2Nzg5MDEyMzQ1Njc4OTAxMjM0NTY3ODkwMTIzNDU2Nzg5MDEyMzQ1Njc4OTAxMjM0NTY3ODkwMTIzNDU2Nzg5MDEyMzQ=",
		UUID:              CARD_UUID,
	})
	req2, _ := http.NewRequest("POST", "/write", bytes.NewBuffer(body2))
	r.ServeHTTP(w2, req2)
	assert.Equal(t, 200, w2.Code)

	w3 := httptest.NewRecorder()
	body3, _ := json.Marshal(types.CardDefinitionRequest{
		Password: 124,
		UUID:     CARD_UUID,
	})
	req3, _ := http.NewRequest("PUT", "/setpassword", bytes.NewBuffer(body3))
	r.ServeHTTP(w3, req3)
	assert.Equal(t, 200, w3.Code)

	w4 := httptest.NewRecorder()
	body4, _ := json.Marshal(types.CardDefinitionRequest{
		Password: 1111111,
		UUID:     CARD_UUID,
	})
	req4, _ := http.NewRequest("PUT", "/read", bytes.NewBuffer(body4))
	r.ServeHTTP(w4, req4)

	assert.Equal(t, 403, w4.Code)

	w5 := httptest.NewRecorder()
	body5, _ := json.Marshal(types.CardDefinitionRequest{
		Password: 1111111,
		UUID:     CARD_UUID,
	})
	req5, _ := http.NewRequest("PUT", "/clearpassword", bytes.NewBuffer(body5))
	r.ServeHTTP(w5, req5)
	assert.Equal(t, 500, w5.Code)
	assert.Contains(t, w5.Body.String(), "invalid password")

	w6 := httptest.NewRecorder()
	body6, _ := json.Marshal(types.CardDefinitionRequest{
		Password: 124,
		UUID:     CARD_UUID,
	})
	req6, _ := http.NewRequest("PUT", "/clearpassword", bytes.NewBuffer(body6))
	r.ServeHTTP(w6, req6)
	assert.Equal(t, 200, w6.Code)

	w7 := httptest.NewRecorder()
	body7, _ := json.Marshal(types.CardDefinitionRequest{
		UUID: CARD_UUID,
	})
	req7, _ := http.NewRequest("PUT", "/read", bytes.NewBuffer(body7))
	r.ServeHTTP(w7, req7)

	assert.Equal(t, 200, w7.Code)

}
