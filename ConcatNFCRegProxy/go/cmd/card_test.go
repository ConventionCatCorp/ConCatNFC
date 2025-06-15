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
	h.env.SetNTAG21xPassword(123)
	r := gin.Default()
	r.PUT("/read", h.readData)
	r.GET("/uuid", h.getUUID)
	r.POST("/write", h.writeData)
	r.PATCH("/write", h.updateData)
	return r
}

func TestCardReadEmpty(t *testing.T) {

	r := setupMock()

	w := httptest.NewRecorder()
	req, _ := http.NewRequest("GET", "/read?password=123&uuid="+CARD_UUID, nil)
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
	req2, _ := http.NewRequest("GET", "/read?password=123&uuid=HUEHUEHUENO!", nil)
	r.ServeHTTP(w2, req2)
	assert.Equal(t, 403, w2.Code)

	w3 := httptest.NewRecorder()
	req3, _ := http.NewRequest("GET", "/read?password=123&uuid="+CARD_UUID, nil)
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
}
