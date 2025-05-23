package tags

import (
	"encoding/binary"

	"ConcatNFCRegProxy/types"
	"fmt"
	"time"
)

var TAG_ATTENDEE_ID byte = 0x01
var TAG_SIGNATURE byte = 0x02
var TAG_ISSUANCE byte = 0x03
var TAG_TIMESTAMP byte = 0x04
var TAG_EXPIRATION byte = 0x05

func TagToText(tag types.Tag) (string, error) {
	switch tag.Id {
	case TAG_ATTENDEE_ID:
		{
			if len(tag.Data) != 8 {
				return "", fmt.Errorf("Tag TAG_ATTENDEE_ID expected 4 bytes but got %d", len(tag.Data))
			}
			val := binary.BigEndian.Uint32(tag.Data)
			val2 := binary.BigEndian.Uint32(tag.Data)
			return fmt.Sprintf("TAG=TAG_ATTENDEE_ID UserID=%d ConventionID=%d", val, val2), nil
		}
	case TAG_SIGNATURE:
		{
			//Im not implementing this rn
			return fmt.Sprintf("TAG=TAG_SIGNATURE Value=%d", 1234), nil
		}
	case TAG_ISSUANCE:
		{
			if len(tag.Data) != 4 {
				return "", fmt.Errorf("Tag TAG_ISSUANCE expected 4 bytes but got %d", len(tag.Data))
			}
			val := binary.BigEndian.Uint32(tag.Data)
			return fmt.Sprintf("TAG=TAG_ISSUANCE Value=%d", val), nil
		}
	case TAG_TIMESTAMP:
		{
			if len(tag.Data) != 8 {
				return "", fmt.Errorf("Tag TAG_TIMESTAMP expected 8 bytes but got %d", len(tag.Data))
			}
			ts := binary.BigEndian.Uint64(tag.Data)
			issued := time.Unix(int64(ts), 0)
			return fmt.Sprintf("TAG=TAG_TIMESTAMP Issued=%s", issued.Format(time.RFC3339)), nil
		}
	case TAG_EXPIRATION:
		{
			if len(tag.Data) != 8 {
				return "", fmt.Errorf("Tag TAG_EXPIRATION expected 8 bytes but got %d", len(tag.Data))
			}
			ts := binary.BigEndian.Uint64(tag.Data)
			issued := time.Unix(int64(ts), 0)
			return fmt.Sprintf("TAG=TAG_TIMESTAMP Expiration=%s", issued.Format(time.RFC3339)), nil
		}
	default:
		{
			return "", fmt.Errorf("Unexpected tag type: %x", tag.Id)
		}
	}
}

func NewAttendeeId(userid uint32, conventionid uint32) types.Tag {
	data := make([]byte, 8)
	binary.BigEndian.PutUint32(data[0:4], userid)
	binary.BigEndian.PutUint32(data[4:8], conventionid)
	return types.Tag{
		Id:   TAG_ATTENDEE_ID,
		Data: data,
	}
}

func NewIssuance(value uint32) types.Tag {
	data := make([]byte, 4)
	binary.BigEndian.PutUint32(data, value)
	return types.Tag{
		Id:   TAG_ISSUANCE,
		Data: data,
	}
}

func NewTimestamp(unixTimestamp uint64) types.Tag {
	data := make([]byte, 8)
	binary.BigEndian.PutUint64(data, unixTimestamp)
	return types.Tag{
		Id:   TAG_TIMESTAMP,
		Data: data,
	}
}

func NewExpiration(unixTimestamp uint64) types.Tag {
	data := make([]byte, 8)
	binary.BigEndian.PutUint64(data, unixTimestamp)
	return types.Tag{
		Id:   TAG_EXPIRATION,
		Data: data,
	}
}

/*
your time will come
func NewSignature(signatureData []byte) types.Tag {
    return types.Tag{
        Id:   TAG_SIGNATURE,
        Data: signatureData, // Assuming variable length signature
    }
}
*/
