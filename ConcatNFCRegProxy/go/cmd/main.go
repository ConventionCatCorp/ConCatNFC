package main

import (
	"net/http"

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

func (h *HandlerContext) getUUID(c *gin.Context) {
	env := h.env

	env.Mtx.Lock()
	defer env.Mtx.Unlock()

	var response types.Response

	if !h.env.IsReady() {
		response.Error = "Card reader not avalible or not ready"
		c.JSON(http.StatusInternalServerError, response)
		return
	}

	statusCode := http.StatusOK
	uid, err := env.GetUUID()
	if err != nil {
		statusCode = http.StatusInternalServerError
		response.Error = err.Error()
	}
	response.UUID = uid

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

	r.Run(":7070")

}
