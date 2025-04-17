package nfc

import (
	"bytes"
	"fmt"
	"log"
	"sync"
	"time"

	"github.com/ebfe/scard"
)

// Opcodes can be found in API-ACR122U-2.04.pdf
var OPERATION_GET_SUPPORTED_CARD_SIGNATURE = []byte{0x3B, 0x8F, 0x80, 0x1, 0x80, 0x4F, 0xC, 0xA0, 0x0, 0x0, 0x3, 0x6, 0x3, 0x0, 0x3}
var OPERATION_GET_CARD_VERSION = []byte{0xff, 0x00, 0x00, 0x00, 0x3, 0xd4, 0x42, 0x60}
var SUPPORTED_CARD = []byte{0x00, 0x04, 0x04, 0x02, 0x01, 0x00}

type NFCEnvoriment struct {
	context *scard.Context
	ready   bool
	Mtx     sync.Mutex
	readers []string
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

	success, body, err := env.transmitAndValidate(card, OPERATION_GET_CARD_VERSION)
	if err != nil {
		return nil, err
	}

	if !success {
		return nil, fmt.Errorf("Operation failed")
	}
	if len(body) < 11 {
		card.Disconnect(scard.ResetCard)
		return nil, fmt.Errorf("Got short response from GET_VERISON")
	}

	if !bytes.Equal(body[3:9], SUPPORTED_CARD) {
		card.Disconnect(scard.ResetCard)
		return nil, fmt.Errorf("Unsupported card: % x\n", rspCodeBytes)
	}

	return card, nil
}

func (env *NFCEnvoriment) GetUUID() (string, error) {

	index, err := env.waitUntilCardPresent(time.Second * 20)
	if err != nil {
		fmt.Printf("Failed to get card information: %s\n", err.Error())
		env.Unready()
		return "", err
	}

	card, err := env.connectAndValidateCard(index)
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
