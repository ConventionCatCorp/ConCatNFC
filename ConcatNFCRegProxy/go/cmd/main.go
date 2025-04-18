package main

import (
	"net/http"
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

	err := env.WriteTags(insertTags)
	if err != nil {
		var response types.Response
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

	r.Run(":7070")

}
