package tags

import (
	"ConcatNFCRegProxy/types"
	"encoding/base64"
	"encoding/binary"
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
			base64Str := base64.StdEncoding.EncodeToString(tag.Data)
			return fmt.Sprintf("TAG=TAG_SIGNATURE Value=%s", base64Str), nil
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

func TagsToRequest(tags []types.Tag) (types.CardDefinitionRequest, error) {
	var resp types.CardDefinitionRequest
	for _, tag := range tags {
		switch tag.Id {
		case TAG_ATTENDEE_ID:
			{
				if len(tag.Data) != 8 {
					return resp, fmt.Errorf("Tag TAG_ATTENDEE_ID expected 4 bytes but got %d", len(tag.Data))
				}
				resp.AttendeeId = binary.BigEndian.Uint32(tag.Data[0:4])
				resp.ConventionId = binary.BigEndian.Uint32(tag.Data[4:8])
			}
		case TAG_SIGNATURE:
			{
				resp.Signature = base64.StdEncoding.EncodeToString(tag.Data)
			}
		case TAG_ISSUANCE:
			{
				if len(tag.Data) != 4 {
					return resp, fmt.Errorf("Tag TAG_ISSUANCE expected 4 bytes but got %d", len(tag.Data))
				}
				resp.IssuanceCount = binary.BigEndian.Uint32(tag.Data)
			}
		case TAG_TIMESTAMP:
			{
				if len(tag.Data) != 8 {
					return resp, fmt.Errorf("Tag TAG_TIMESTAMP expected 8 bytes but got %d", len(tag.Data))
				}
				resp.IssuanceTimestamp = binary.BigEndian.Uint64(tag.Data)
			}
		case TAG_EXPIRATION:
			{
				if len(tag.Data) != 8 {
					return resp, fmt.Errorf("Tag TAG_EXPIRATION expected 8 bytes but got %d", len(tag.Data))
				}
				resp.Expiration = binary.BigEndian.Uint64(tag.Data)
			}
		default:
			{
				return resp, fmt.Errorf("Unexpected tag type: %x", tag.Id)
			}
		}
	}
	return resp, nil
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

func NewSignature(signatureData []byte) types.Tag {
	return types.Tag{
		Id:   TAG_SIGNATURE,
		Data: signatureData,
	}
}

func ValidateSignatureStructure(str string) ([]byte, error) {
	data, err := base64.StdEncoding.DecodeString(str)
	if err != nil {
		return []byte{}, err
	}

	return data, nil
}

func UpdateTagAttendee(tags types.Tag, data types.CardDefinitionRequest) (types.Tag, error) {
	if data.AttendeeId == 0 || data.ConventionId == 0 {
		return types.Tag{}, fmt.Errorf("'attendeeId' and 'conventionId' should not be zero or empty")
	}
	return NewAttendeeId(data.AttendeeId, data.ConventionId), nil
}

func UpdateTags(tags []types.Tag, data types.CardDefinitionRequest) ([]types.Tag, error) {
	var err error
	var content types.Tag
	for idx, tag := range tags {
		if tag.Id == TAG_ATTENDEE_ID {
			if data.AttendeeId != 0 || data.ConventionId != 0 {
				content, err = UpdateTagAttendee(tag, data)
				if err != nil {
					return []types.Tag{}, err
				}
				tags[idx] = content
			}
		} else if tag.Id == TAG_ISSUANCE {
			if data.IssuanceCount != 0 {
				tags[idx] = NewIssuance(data.IssuanceCount)
			}
		} else if tag.Id == TAG_TIMESTAMP {
			if data.IssuanceTimestamp != 0 {
				tags[idx] = NewTimestamp(data.IssuanceTimestamp)
			}
		} else if tag.Id == TAG_EXPIRATION {
			if data.Expiration != 0 {
				tags[idx] = NewExpiration(data.Expiration)
			}
		} else if tag.Id == TAG_SIGNATURE {
			if data.Signature != "" {
				sign, err := ValidateSignatureStructure(data.Signature)
				if err != nil {
					return []types.Tag{}, err
				}
				tags[idx] = NewSignature(sign)
			}
		}
	}
	return tags, nil
}
