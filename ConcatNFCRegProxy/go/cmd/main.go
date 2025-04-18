package main

import (
	"net/http"
	"strconv"

	"ConcatNFCRegProxy/internal/nfc"
	"ConcatNFCRegProxy/types"

	"github.com/gin-gonic/gin"
)

type HandlerContext struct {
	env *nfc.NFCEnvoriment
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

// NOTE: This function has h.env.Mtx held on return
func (h *HandlerContext) waitForCardReady(c *gin.Context) bool {
	env := h.env

	env.Mtx.Lock()

	var response types.Response

	if !h.env.IsReady() {
		response.Error = "Card reader not avalible or not ready"
		c.JSON(http.StatusInternalServerError, response)
		return false
	}
	return true
}

func (h *HandlerContext) getUUID(c *gin.Context) {
	env := h.env
	success := h.waitForCardReady(c)
	defer env.Mtx.Unlock()
	if !success {
		return
	}

	var response types.Response

	statusCode := http.StatusOK
	uid, err := env.GetUUID()
	if err != nil {
		statusCode = http.StatusInternalServerError
		response.Error = err.Error()
	}
	response.UUID = uid

	c.JSON(statusCode, response)

}

func (h *HandlerContext) setPassword(c *gin.Context) {
	var response types.Response
	password := c.Query("password")
	if password == "" {
		response.Error = "missing password parameter"
		c.JSON(http.StatusBadRequest, response)
		return
	}
	passwordUint64, err := strconv.ParseUint(password, 0, 32)
	if err != nil {
		response.Error = err.Error()
		c.JSON(http.StatusBadRequest, response)
		return
	}
	passwordUint32 := uint32(passwordUint64)

	env := h.env
	success := h.waitForCardReady(c)
	defer env.Mtx.Unlock()
	if !success {
		return
	}

	statusCode := http.StatusOK
	err = env.SetNTAG21xPassword(passwordUint32)
	if err != nil {
		statusCode = http.StatusInternalServerError
		response.Error = err.Error()
	}

	c.JSON(statusCode, response)

}

func main() {

	handler := HandlerContext{
		env: nfc.BeginNfc(),
	}

	/*for {
		if handler.env.IsReady() {
			uid, err := handler.env.GetUUID()
			if err != nil {
				fmt.Printf("Err: %s\n", err.Error())
			} else {
				fmt.Printf("Found: %s\n", uid)
			}
		}
		time.Sleep(1 * time.Second)
	}*/

	gin.SetMode(gin.ReleaseMode)
	r := gin.Default()

	r.GET("/healthcheck", handler.healthcheck)
	r.GET("/uuid", handler.getUUID)
	r.GET("/setpassword", handler.setPassword)

	r.Run(":7070")

}
