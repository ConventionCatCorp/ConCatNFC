package main

import (
	"net/http"
	"strconv"
	"time"

	"ConcatNFCRegProxy/internal/nfc"
	"ConcatNFCRegProxy/internal/tags"
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

// NOTE: This function has h.env.Mtx held on return. Call releaseCard when done.
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

func (h *HandlerContext) releaseCard() {
	h.env.Mtx.Unlock()
}

func (h *HandlerContext) getUUID(c *gin.Context) {
	env := h.env
	success := h.waitForCardReady(c)
	defer h.releaseCard()
	if !success {
		return
	}

	var response types.Response

	statusCode := http.StatusOK
	err := env.StartConnection()
	if err != nil {
		response.Error = err.Error()
		c.JSON(http.StatusInternalServerError, response)
		return
	}
	defer env.EndConnection()
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
	defer h.releaseCard()
	if !success {
		return
	}

	statusCode := http.StatusOK
	err = env.StartConnection()
	if err != nil {
		statusCode = http.StatusInternalServerError
		response.Error = err.Error()
		c.JSON(statusCode, response)
		return
	}
	defer env.EndConnection()

	err = env.SetNTAG21xPassword(passwordUint32)
	if err != nil {
		statusCode = http.StatusInternalServerError
		response.Error = err.Error()
	}

	c.JSON(statusCode, response)
}

func (h *HandlerContext) clearPassword(c *gin.Context) {
	var response types.Response
	env := h.env
	success := h.waitForCardReady(c)
	defer h.releaseCard()
	if !success {
		return
	}

	statusCode := http.StatusOK
	err := env.StartConnection()
	if err != nil {
		statusCode = http.StatusInternalServerError
		response.Error = err.Error()
		c.JSON(statusCode, response)
		return
	}
	defer env.EndConnection()

	var passwordUint32 uint32
	password := c.Query("password")
	if password != "" {
		passwordUint64, err := strconv.ParseUint(password, 0, 32)
		if err != nil {
			response.Error = err.Error()
			c.JSON(http.StatusBadRequest, response)
			return
		}
		passwordUint32 = uint32(passwordUint64)
		err = env.NTAG21xAuth(passwordUint32)
		if err != nil {
			statusCode = http.StatusInternalServerError
			response.Error = err.Error()
			c.JSON(statusCode, response)
			return
		}
	}

	err = env.ClearNTAG21xPassword()
	if err != nil {
		statusCode = http.StatusInternalServerError
		response.Error = err.Error()
	}

	c.JSON(statusCode, response)
}

func (h *HandlerContext) unlockCard(c *gin.Context) {
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
	defer h.releaseCard()
	if !success {
		return
	}

	statusCode := http.StatusOK
	err = env.NTAG21xAuth(passwordUint32)
	if err != nil {
		statusCode = http.StatusInternalServerError
		response.Error = err.Error()
	}

	c.JSON(statusCode, response)

}

func (h *HandlerContext) writeTagsTest(c *gin.Context) {
	env := h.env

	env.Mtx.Lock()
	defer env.Mtx.Unlock()

	var insertTags []types.Tag
	insertTags = append(insertTags, tags.NewAttendeeId(123, 0xff))
	insertTags = append(insertTags, tags.NewAttendeeId(123, 0xff))
	insertTags = append(insertTags, tags.NewIssuance(2))
	insertTags = append(insertTags, tags.NewTimestamp(uint64(time.Now().Unix())))
	insertTags = append(insertTags, tags.NewExpiration(uint64(time.Now().Unix()+3600*24)))
	insertTags = append(insertTags, tags.NewTimestamp(uint64(time.Now().Unix())))

	err := env.StartConnection()
	if err != nil {
		var response types.Response
		response.Error = err.Error()
		c.JSON(http.StatusInternalServerError, response)
		return
	}
	defer env.EndConnection()

	password := c.Query("password")
	if password != "" {
		passwordUint64, err := strconv.ParseUint(password, 0, 32)
		if err != nil {
			var response types.Response
			response.Error = err.Error()
			c.JSON(http.StatusBadRequest, response)
			return
		}
		passwordUint32 := uint32(passwordUint64)
		err = env.NTAG21xAuth(passwordUint32)
		if err != nil {
			var response types.Response
			response.Error = "Invalid authentication " + err.Error()
			c.JSON(http.StatusForbidden, response)
			return
		}
		// Continue with your password processing here...
	}

	err = env.WriteTags(insertTags)
	if err != nil {
		var response types.Response
		if env.IsAuthRequired() {
			response.Error = "Password required"
			c.JSON(http.StatusForbidden, response)
			return
		}
		response.Error = err.Error()
		c.JSON(http.StatusInternalServerError, response)
		return
	}

	c.JSON(http.StatusOK, len(insertTags))

}

func (h *HandlerContext) getAllTags(c *gin.Context) {
	env := h.env

	env.Mtx.Lock()
	defer env.Mtx.Unlock()

	var response types.Response

	if !h.env.IsReady() {
		response.Error = "Card reader not avalible or not ready"
		c.JSON(http.StatusInternalServerError, response)
		return
	}

	err := env.StartConnection()
	if err != nil {
		response.Error = err.Error()
		c.JSON(http.StatusInternalServerError, response)
		return
	}
	defer env.EndConnection()

	uid, err := env.GetUUID()
	if err != nil {
		response.Error = err.Error()
		c.JSON(http.StatusInternalServerError, response)
		return
	}

	paramUuid := c.Params.ByName("uuid")
	if paramUuid != uid {
		response.Error = "Mismatched tag UUID"
		c.JSON(http.StatusForbidden, response)
		return
	}

	err = env.StartConnection()
	if err != nil {
		response.Error = err.Error()
		c.JSON(http.StatusInternalServerError, response)
		return
	}

	defer env.EndConnection()

	password := c.Query("password")
	if password != "" {
		passwordUint64, err := strconv.ParseUint(password, 0, 32)
		if err != nil {
			var response types.Response
			response.Error = err.Error()
			c.JSON(http.StatusBadRequest, response)
			return
		}
		passwordUint32 := uint32(passwordUint64)
		err = env.NTAG21xAuth(passwordUint32)
		if err != nil {
			var response types.Response
			response.Error = "Invalid authentication " + err.Error()
			c.JSON(http.StatusForbidden, response)
			return
		}
		// Continue with your password processing here...
	}

	readTags, err := env.ReadTags()
	if err != nil {
		response.Error = err.Error()
		c.JSON(http.StatusInternalServerError, response)
		return
	}
	var results []string
	for _, tag := range readTags {
		str, err := tags.TagToText(tag)
		if err != nil {
			response.Error = err.Error()
			c.JSON(http.StatusInternalServerError, response)
			return
		}
		results = append(results, str)
	}

	c.JSON(http.StatusOK, results)
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
	r.GET("/read/:uuid/all", handler.getAllTags)

	r.GET("/write_tags/test", handler.writeTagsTest)
	r.GET("/setpassword", handler.setPassword)
	r.GET("/clearpassword", handler.clearPassword)
	r.GET("/unlockcard", handler.unlockCard)

	r.Run(":7070")

}
