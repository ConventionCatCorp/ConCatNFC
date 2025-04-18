package nfc

import (
	"bytes"
	"encoding/binary"
	"fmt"
	"log"
	"strings"
	"sync"
	"time"

	"github.com/ebfe/scard"
)

// Opcodes can be found in API-ACR122U-2.04.pdf
var OPERATION_GET_SUPPORTED_CARD_SIGNATURE = []byte{0x3B, 0x8F, 0x80, 0x1, 0x80, 0x4F, 0xC, 0xA0, 0x0, 0x0, 0x3, 0x6, 0x3, 0x0, 0x3}
var SUPPORTED_CARD = []byte{0x00, 0x04, 0x04, 0x02, 0x01, 0x00}

type NFCEnvoriment struct {
	context *scard.Context
	ready   bool
	Mtx     sync.Mutex
	readers []string
	version []byte
}

type CardInfo struct {
	Manufacturer string
	ProductName  string
	Memory       int
}

func BeginNfc() *NFCEnvoriment {
	var env NFCEnvoriment
	var err error
	env.context, err = scard.EstablishContext()
	if err != nil {
		fmt.Printf("Cannot establish connection to scard: %u", err)
		return nil
	}

	go env.lookForDevicesRoutine()

	return &env
}

func (env *NFCEnvoriment) IsReady() bool {
	if !env.ready {
		return false
	}
	valid, err := env.context.IsValid()
	if !valid {
		fmt.Printf("Lost connection to the scard %s", err.Error())
		env.Unready()
	}
	return env.ready
}

func (env *NFCEnvoriment) lookForDevicesRoutine() {
	var err error
	env.Mtx.Lock()
	for {
		readers, err := env.context.ListReaders()
		if err != nil {
			fmt.Printf("No device found %s!\n", err.Error())
			time.Sleep(1 * time.Second)
			continue
		}
		//We'll be using the first connected device.
		//In the case of multiple devices connected~
		if len(readers) > 0 {
			fmt.Printf("Found a device, those are our readers: %v\n", readers)
			env.readers = []string{readers[0]}
			env.ready = true
			break
		}
	}
	env.Mtx.Unlock()
	//Now periodically we check if the device have been disconnected, if so we drop restart.
	for {
		if !env.IsReady() {
			fmt.Println("Context might be broken, restarting")
			env.ready = false
			env.Mtx.Lock()
			defer env.Mtx.Unlock()
			for {
				env.context, err = scard.EstablishContext()
				if err != nil {
					fmt.Printf("Cannot establish connection to scard: %s\n", err.Error())
					continue
				}
				break
			}
			break
		}
		time.Sleep(1 * time.Second)
	}
	env.lookForDevicesRoutine()
}

func (env *NFCEnvoriment) Unready() {
	env.ready = false
}

func (env *NFCEnvoriment) waitUntilCardPresent(maxWaitTime time.Duration) (int, error) {
	if len(env.readers) == 0 {
		return -1, fmt.Errorf("No card readers avaliable")
	}
	//Im leaving it here just in case we decide to use multiple card readers, blep
	rs := make([]scard.ReaderState, len(env.readers))
	for i := range rs {
		rs[i].Reader = env.readers[i]
		rs[i].CurrentState = scard.StateUnaware
	}

	started := time.Now().Add(maxWaitTime)

	for {
		for i := range rs {
			if rs[i].EventState&scard.StatePresent != 0 {
				return i, nil
			}
			rs[i].CurrentState = rs[i].EventState
		}
		err := env.context.GetStatusChange(rs, -1)
		if err != nil {
			return -1, err
		}
		if time.Now().After(started) {
			return -1, fmt.Errorf("Timed out")
		}
	}
}

func (env *NFCEnvoriment) transmitAndValidate(card *scard.Card, message []byte) (bool, []byte, error) {
	rsp, err := card.Transmit(message)
	if err != nil {
		return false, []byte{}, err
	}

	if len(rsp) < 2 {
		log.Fatal("Not enough bytes in answer. Try again")
		return false, []byte{}, fmt.Errorf("Unexpected response")
	}

	rspCodeBytes := rsp[len(rsp)-2:]
	successResponseCode := []byte{0x90, 0x00}
	if !bytes.Equal(rspCodeBytes, successResponseCode) {
		return false, rsp[0 : len(rsp)-2], fmt.Errorf("Operation failed to complete. Error code % x\n", rspCodeBytes)
	}
	return true, rsp[0 : len(rsp)-2], nil
}

func (env *NFCEnvoriment) readData(card *scard.Card, offset byte, length byte) ([]byte, error) {
	success, body, err := env.transmitAndValidate(card, []byte{0xFF, 0xB0, 0x00, offset, length})
	if err != nil {
		return nil, err
	}
	if !success {
		return nil, fmt.Errorf("unknown failure")
	}
	return body, nil
}

func (env *NFCEnvoriment) writeData(card *scard.Card, offset byte, data []byte) error {
	dataLen := len(data)
	if dataLen > 0xFF {
		return fmt.Errorf("Data too long")
	}
	dataToSend := []byte{0xFF, 0xd6, 0x00, offset, byte(dataLen)}
	dataToSend = append(dataToSend, data...)
	card.BeginTransaction()
	defer card.EndTransaction(scard.LeaveCard)
	success, _, err := env.transmitAndValidate(card, dataToSend)
	if err != nil {
		return err
	}
	if !success {
		return fmt.Errorf("unknown failure")
	}
	return nil
}

func (env *NFCEnvoriment) transmitVendorCommand(card *scard.Card, vendorCommand []byte) (bool, []byte, error) {
	length := 2 + len(vendorCommand)
	if length > 0xff {
		return false, []byte{}, fmt.Errorf("Vendor command is too large (%d)", length)
	}
	var command []byte
	command = append(command, []byte{0xff, 0x00, 0x00, 0x00, byte(length), 0xd4, 0x42}...)
	command = append(command, vendorCommand...)
	return env.transmitAndValidate(card, command)
}

func (env *NFCEnvoriment) connectAndValidateCard(index int) (*scard.Card, error) {
	card, err := env.context.Connect(env.readers[index], scard.ShareShared, scard.ProtocolAny)
	if err != nil {
		fmt.Printf("Failed to connect to card: %s\n", err.Error())
		env.Unready()
		return nil, err
	}

	status, err := card.Status()
	if err != nil {
		return nil, err
	}

	if len(status.Atr) < 15 {
		card.Disconnect(scard.ResetCard)
		return nil, fmt.Errorf("Card ATR is too short")
	}
	// Need to check for MIFARE Ultralight
	rspCodeBytes := status.Atr[:15]
	if !bytes.Equal(rspCodeBytes, OPERATION_GET_SUPPORTED_CARD_SIGNATURE) {
		card.Disconnect(scard.ResetCard)
		return nil, fmt.Errorf("Operation failed to complete. Error code % x\n", rspCodeBytes)
	}

	success, version, err := env.transmitVendorCommand(card, []byte{0x60})
	if err != nil {
		return nil, err
	}
	env.version = version[3:]

	if !success {
		return nil, fmt.Errorf("Operation failed")
	}
	if len(version) < 11 {
		card.Disconnect(scard.ResetCard)
		return nil, fmt.Errorf("Got short response from GET_VERISON")
	}

	if !bytes.Equal(version[3:9], SUPPORTED_CARD) {
		card.Disconnect(scard.ResetCard)
		return nil, fmt.Errorf("Unsupported card: % x\n", rspCodeBytes)
	}

	return card, nil
}

func (env *NFCEnvoriment) connectToFirstCard() (*scard.Card, error) {
	index, err := env.waitUntilCardPresent(time.Second * 20)
	if err != nil {
		fmt.Printf("Failed to get card information: %s\n", err.Error())
		env.Unready()
		return nil, err
	}

	card, err := env.connectAndValidateCard(index)
	if err != nil {
		return nil, err
	}
	return card, nil
}

func (env *NFCEnvoriment) GetUUID() (string, error) {
	card, err := env.connectToFirstCard()
	if err != nil {
		return "", err
	}
	defer card.Disconnect(scard.ResetCard)

	success, body, err := env.transmitAndValidate(card, []byte{0xFF, 0xCA, 0x00, 0x00, 0x00})
	if err != nil {
		return "", err
	}

	if !success {
		return "", fmt.Errorf("Operation failed")
	}

	return fmt.Sprintf("% x", body), nil
}

func (env *NFCEnvoriment) GetCardInfo() (ci *CardInfo, err error) {
	card, err := env.connectToFirstCard()
	if err != nil {
		return nil, err
	}
	defer card.Disconnect(scard.ResetCard)
	ci = new(CardInfo)

	switch env.version[1] {
	case 0x04: // NXP
		ci.Manufacturer = "NXP Semiconductors"
		switch env.version[2] {
		case 0x04: // NTAG
			switch env.version[3] {
			case 0x02: // 50 pF
				switch env.version[4] {
				case 0x01:
					switch env.version[5] {
					case 0x00: // V0 - NTAG21x
						switch env.version[6] {
						case 0x0f: // NTAG213
							ci.Memory = 144
							ci.ProductName = "NTAG213"
						case 0x11: // NTAG215
							ci.Memory = 504
							ci.ProductName = "NTAG215"
						case 0x13: // NTAG216
							ci.Memory = 888
							ci.ProductName = "NTAG216"
						default:
							return nil, fmt.Errorf("Unsupported card")
						}
					default:
						return nil, fmt.Errorf("Unsupported card")
					}
				default:
					return nil, fmt.Errorf("Unsupported card")
				}
			default:
				return nil, fmt.Errorf("Unsupported card")
			}
		default:
			return nil, fmt.Errorf("Unsupported card")
		}
	default:
		return nil, fmt.Errorf("Unsupported card")
	}
	return ci, nil
}

func (env *NFCEnvoriment) SetNTAG21xPassword(password uint32) error {
	card, err := env.connectToFirstCard()
	if err != nil {
		return err
	}
	defer card.Disconnect(scard.ResetCard)

	ci, err := env.GetCardInfo()
	if err != nil {
		return err
	}
	if !strings.HasPrefix(ci.Manufacturer, "NXP") || !strings.HasPrefix(ci.ProductName, "NTAG21") {
		return fmt.Errorf("Only NXP NTAG21x supports password")
	}
	data, err := env.readData(card, 0xa, 4)
	fmt.Printf("data: %v", data)
	err = env.writeData(card, 0xb, []byte{0x11, 0x22, 0x33, 0x44})
	if err != nil {
		fmt.Printf("error: %s", err)
	}

	passwordBytes := make([]byte, 4)
	binary.BigEndian.PutUint32(passwordBytes, password)
	switch ci.ProductName {
	case "NTAG213":
		err = env.writeData(card, 0x2b, passwordBytes)
	case "NTAG215":
		err = env.writeData(card, 0x85, passwordBytes)
	case "NTAG216":
		err = env.writeData(card, 0xe5, passwordBytes)
	default:
		return fmt.Errorf("Unsupported %s", ci.ProductName)
	}
	if err != nil {
		return err
	}
	return nil
}
