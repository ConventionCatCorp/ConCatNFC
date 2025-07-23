package main

import (
	"ConcatNFCRegProxy/broker"
	"fmt"
	"io"
	"net/http"
	"strconv"
	"time"

	"ConcatNFCRegProxy/internal/nfc"
	"ConcatNFCRegProxy/internal/tags"
	"ConcatNFCRegProxy/types"

	"github.com/gin-gonic/gin"
)

type NFCInterface interface {
	IsReady() bool
	GetUUID() (string, error)
	SetNTAG21xPassword(password uint32) error
	IsAuthRequired() bool
	NTAG21xAuth(password uint32) error
	BeepReader() error
	WriteTags(tags []types.Tag) error
	ReadTags() ([]types.Tag, error)
	Lock()
	Unlock()
	ClearNTAG21xPassword() error
}

type HandlerContext struct {
	env NFCInterface
	b   *broker.Broker[string]
}

func (h *HandlerContext) healthcheck(c *gin.Context) {
	if h.env.IsReady() {
		c.JSON(http.StatusOK, gin.H{
			"ready": true,
		})
	} else {
		c.JSON(http.StatusInternalServerError, gin.H{
			"ready": false,
		})
	}
}

// NOTE: This function has h.env.Mtx held on return. Call releaseCard when done.
func (h *HandlerContext) waitForCardReady(c *gin.Context) bool {
	env := h.env

	env.Lock()

	if !h.env.IsReady() {
		return false
	}
	return true
}

func (h *HandlerContext) releaseCard() {
	h.env.Unlock()
}

func (h *HandlerContext) getUUID(c *gin.Context) {
	var response types.Response

	env := h.env
	success := h.waitForCardReady(c)
	defer h.releaseCard()
	if !success {
		response.Error = fmt.Sprintf("Card not ready")
		c.JSON(http.StatusInternalServerError, response)
		return
	}

	statusCode := http.StatusOK
	uid, err := env.GetUUID()
	if err != nil {
		statusCode = http.StatusUnsupportedMediaType
		response.Error = err.Error()
		c.JSON(statusCode, response)
		return

	}
	response.UUID = uid
	response.Success = true
	c.JSON(statusCode, response)

}

func (h *HandlerContext) readData(c *gin.Context) {
	var response types.Response
	var err error

	var req types.CardReadSetPasswordRequest

	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": "Invalid request body"})
		return
	}

	if req.UUID == "" {
		c.JSON(http.StatusBadRequest, gin.H{"error": "Invalid request body, one of the fields are missing"})
		return
	}

	nullPassword := false
	if req.Password == 0 {
		nullPassword = true
		req.Password = 0xffffffff
	}

	env := h.env
	success := h.waitForCardReady(c)
	defer h.releaseCard()
	if !success {
		return
	}

	uid, err := env.GetUUID()
	if err != nil {
		response.Error = err.Error()
		c.JSON(http.StatusInternalServerError, response)
		return
	}

	if uid != req.UUID {
		response.Error = "Mismatched card UUID. Did you swapped the card between operations? Current UUID=" + uid
		c.JSON(http.StatusForbidden, response)
		return
	}

	err = env.NTAG21xAuth(req.Password)
	if err != nil {
		if err.Error() == "Operation failed to complete. Error code 63 00\n" && nullPassword == false {
			time.Sleep(1000 * time.Millisecond)
			err = env.NTAG21xAuth(req.Password)
		}
		if err != nil {
			response.Error = "Invalid authentication " + err.Error()
			c.JSON(http.StatusForbidden, response)
			return
		}
	}

	readTags, err := env.ReadTags()
	if err != nil {
		response.Error = err.Error()
		c.JSON(http.StatusInternalServerError, response)
		return
	}

	if len(readTags) == 0 {
		response.Error = "Card is empty!"
		c.JSON(http.StatusExpectationFailed, response)
		return
	}

	content, err := tags.TagsToRequest(readTags)
	if err != nil {
		response.Error = err.Error()
		c.JSON(http.StatusInternalServerError, response)
		return
	}

	response.Card = &content
	response.Success = true
	c.JSON(http.StatusOK, response)

}

func (h *HandlerContext) writeData(c *gin.Context) {
	var req types.CardDefinitionRequest

	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": "Invalid request body"})
		return
	}

	if req.AttendeeId == 0 || req.ConventionId == 0 || req.IssuanceCount == 0 ||
		req.IssuanceTimestamp == "" || req.Signature == "" || req.Password == 0 || req.UUID == "" {
		c.JSON(http.StatusBadRequest, gin.H{"error": "Invalid request body, one of the fields are missing"})
		return
	}
	var response types.Response

	env := h.env
	success := h.waitForCardReady(c)
	defer h.releaseCard()
	if !success {
		return
	}

	uid, err := env.GetUUID()
	if err != nil {
		response.Error = err.Error()
		c.JSON(http.StatusInternalServerError, response)
		return
	}

	if uid != req.UUID {
		response.Error = "Mismatched card UUID. Did you swapped the card between operations? Current UUID=" + uid
		c.JSON(http.StatusForbidden, response)
		return
	}

	var insertTags []types.Tag
	insertTags = append(insertTags, tags.NewAttendeeId(req.AttendeeId, req.ConventionId))
	insertTags = append(insertTags, tags.NewIssuance(req.IssuanceCount))
	timestamp, err := strconv.ParseUint(req.IssuanceTimestamp, 10, 64)
	if err != nil {
		response.Error = "Invalid timestamp: " + err.Error()
		c.JSON(http.StatusForbidden, response)
	}
	insertTags = append(insertTags, tags.NewTimestamp(timestamp))
	if req.Expiration != 0 {
		insertTags = append(insertTags, tags.NewExpiration(req.Expiration))
	}
	bytessign, err := tags.ValidateSignatureStructure(req.Signature)

	if err != nil {
		response.Error = "Invalid authentication " + err.Error()
		c.JSON(http.StatusForbidden, response)
		return
	}
	insertTags = append(insertTags, tags.NewSignature(bytessign))

	err = env.NTAG21xAuth(req.Password)
	if err != nil {
		response.Error = "Invalid authentication " + err.Error()
		c.JSON(http.StatusForbidden, response)
		return
	}

	err = env.WriteTags(insertTags)

	if err != nil {
		response.Error = err.Error()
		c.JSON(http.StatusInternalServerError, response)
		return
	}
	_ = env.BeepReader()
	response.Success = true
	c.JSON(http.StatusOK, response)
}

func (h *HandlerContext) updateData(c *gin.Context) {
	var req types.CardDefinitionRequest

	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": "Invalid request body"})
		return
	}

	if req.Signature == "" || req.Password == 0 || req.UUID == "" {
		c.JSON(http.StatusBadRequest, gin.H{"error": "Invalid request body, fields signature, password and uuid are required"})
		return
	}
	var response types.Response

	env := h.env
	success := h.waitForCardReady(c)
	defer h.releaseCard()
	if !success {
		return
	}

	uid, err := env.GetUUID()
	if err != nil {
		response.Error = err.Error()
		c.JSON(http.StatusInternalServerError, response)
		return
	}

	if uid != req.UUID {
		response.Error = "Mismatched card UUID. Did you swapped the card between operations? Current UUID=" + uid
		c.JSON(http.StatusForbidden, response)
		return
	}

	err = env.NTAG21xAuth(req.Password)
	if err != nil {
		response.Error = "Invalid authentication " + err.Error()
		c.JSON(http.StatusForbidden, response)
		return
	}

	readTags, err := env.ReadTags()
	if err != nil {
		response.Error = err.Error()
		c.JSON(http.StatusInternalServerError, response)
		return
	}

	newTags, err := tags.UpdateTags(readTags, req)
	if err != nil {
		response.Error = err.Error()
		c.JSON(http.StatusInternalServerError, response)
		return
	}

	err = env.WriteTags(newTags)
	if err != nil {
		response.Error = err.Error()
		c.JSON(http.StatusInternalServerError, response)
		return
	}

	response.Success = true
	c.JSON(http.StatusOK, response)
}

func (h *HandlerContext) setPassword(c *gin.Context) {
	var response types.Response

	var req types.CardReadSetPasswordRequest

	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": "Invalid request body"})
		return
	}

	if req.UUID == "" {
		c.JSON(http.StatusBadRequest, gin.H{"error": "Invalid request body, one of the fields are missing"})
		return
	}
	if req.Password == 0 {
		response.Error = "missing password parameter"
		c.JSON(http.StatusBadRequest, response)
		return
	}

	env := h.env
	success := h.waitForCardReady(c)
	defer h.releaseCard()
	if !success {
		response.Error = "Card did not become ready"
		c.JSON(http.StatusInternalServerError, response)
		return

	}

	statusCode := http.StatusOK

	uid, err := env.GetUUID()
	if err != nil {
		response.Error = err.Error()
		c.JSON(http.StatusInternalServerError, response)
		return
	}

	if uid != req.UUID {
		response.Error = "Mismatched card UUID. Did you swapped the card between operations? Current UUID=" + uid
		c.JSON(http.StatusForbidden, response)
		return
	}

	err = env.SetNTAG21xPassword(req.Password)
	if err != nil {
		statusCode = http.StatusInternalServerError
		response.Error = err.Error()
		c.JSON(http.StatusInternalServerError, response)
		return
	}

	response.Success = true
	c.JSON(statusCode, response)
}

func (h *HandlerContext) clearPassword(c *gin.Context) {
	var response types.Response

	var req types.CardReadSetPasswordRequest

	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": "Invalid request body"})
		return
	}

	if req.UUID == "" {
		c.JSON(http.StatusBadRequest, gin.H{"error": "Invalid request body, one of the fields are missing"})
		return
	}
	if req.Password == 0 {
		response.Error = "missing password parameter"
		c.JSON(http.StatusBadRequest, response)
		return
	}

	env := h.env
	success := h.waitForCardReady(c)
	defer h.releaseCard()
	if !success {
		return
	}

	statusCode := http.StatusOK

	uid, err := env.GetUUID()
	if err != nil {
		response.Error = err.Error()
		c.JSON(http.StatusInternalServerError, response)
		return
	}

	if uid != req.UUID {
		response.Error = "Mismatched card UUID. Did you swapped the card between operations? Current UUID=" + uid
		c.JSON(http.StatusForbidden, response)
		return
	}

	err = env.NTAG21xAuth(req.Password)
	if err != nil {
		statusCode = http.StatusInternalServerError
		response.Error = err.Error()
		c.JSON(statusCode, response)
		return
	}

	err = env.ClearNTAG21xPassword()
	if err != nil {
		statusCode = http.StatusInternalServerError
		response.Error = err.Error()
	}
	response.Success = true
	c.JSON(statusCode, response)
}

func (h *HandlerContext) sseHandler(c *gin.Context) {
	// Set the headers for SSE
	c.Writer.Header().Set("Content-Type", "text/event-stream")
	c.Writer.Header().Set("Cache-Control", "no-cache")
	c.Writer.Header().Set("Connection", "keep-alive")
	c.Writer.Header().Set("Transfer-Encoding", "chunked")

	// Create a channel to send events
	eventChan := h.b.Subscribe()

	// Write events to client
	c.Stream(func(w io.Writer) bool {
		if event, ok := <-eventChan; ok {
			_, err := w.Write([]byte(event))
			return err == nil
		}
		return false
	})
}

func CORSMiddleware() gin.HandlerFunc {
	return func(c *gin.Context) {
		c.Writer.Header().Set("Access-Control-Allow-Origin", "*")
		c.Writer.Header().Set("Access-Control-Allow-Credentials", "true")
		c.Writer.Header().Set("Access-Control-Allow-Headers", "Content-Type, Content-Length, Accept-Encoding, X-CSRF-Token, Authorization, accept, origin, Cache-Control, X-Requested-With")
		c.Writer.Header().Set("Access-Control-Allow-Methods", "POST, OPTIONS, GET, PUT")

		if c.Request.Method == "OPTIONS" {
			c.AbortWithStatus(204)
			return
		}

		c.Next()
	}
}

func main() {
	b := broker.NewBroker[string]()
	go b.Start()

	handler := HandlerContext{
		env: nfc.BeginNfc(b),
		b:   b,
	}

	gin.SetMode(gin.ReleaseMode)
	r := gin.Default()

	r.Use(CORSMiddleware())

	r.GET("/healthcheck", handler.healthcheck)
	r.GET("/uuid", handler.getUUID)

	r.POST("/write", handler.writeData)
	r.PATCH("/write", handler.updateData)
	r.PUT("/read", handler.readData)
	r.PUT("/setpassword", handler.setPassword)
	r.PUT("/clearpassword", handler.clearPassword)

	r.GET("/events", handler.sseHandler)

	r.Run(":7070")

}
