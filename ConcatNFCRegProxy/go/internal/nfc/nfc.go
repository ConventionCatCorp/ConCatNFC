package nfc

import (
	"bytes"
	"encoding/binary"
	"fmt"
	"log"
	"strings"
	"sync"
	"time"

	"ConcatNFCRegProxy/types"

	"github.com/ebfe/scard"
)

var STARTING_REGION byte = 0x10
var PAGE_SIZE byte = 0x04

// Opcodes can be found in API-ACR122U-2.04.pdf
var OPERATION_GET_SUPPORTED_CARD_SIGNATURE = []byte{0x3B, 0x8F, 0x80, 0x1, 0x80, 0x4F, 0xC, 0xA0, 0x0, 0x0, 0x3, 0x6, 0x3, 0x0, 0x3}
var SUPPORTED_CARD = []byte{0x00, 0x04, 0x04, 0x02, 0x01, 0x00}
var OPERATION_READ = []byte{0xFF, 0xB0, 0x00, 0x00, PAGE_SIZE}
var OPERATION_WRITE = []byte{0xFF, 0xD6, 0x00, 0x00, PAGE_SIZE}

type NFCEnvoriment struct {
	context        *scard.Context
	ready          bool
	Mtx            sync.Mutex
	readers        []string
	version        []byte
	cardConnection *scard.Card
	buffer         []byte
	currentPage    byte
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

// transmitVendorCommand implements inCommunicateThru command according to NXP App note 157830_PN533 section 8.4.9
func (env *NFCEnvoriment) transmitVendorCommand(card *scard.Card, vendorCommand []byte) (bool, []byte, error) {
	length := 2 + len(vendorCommand)
	if length > 0xff {
		return false, []byte{}, fmt.Errorf("Vendor command is too large (%d)", length)
	}
	var command []byte
	command = append(command, []byte{0xff, 0x00, 0x00, 0x00, byte(length), 0xd4, 0x42}...)
	command = append(command, vendorCommand...)
	success, resp, err := env.transmitAndValidate(card, command)
	if err != nil {
		return false, []byte{}, err
	}
	if !success {
		return success, resp, err
	}
	if len(resp) < 3 {
		return false, []byte{}, fmt.Errorf("response too short")
	}
	if resp[0] != 0xd5 || resp[1] != 0x43 {
		return false, []byte{}, fmt.Errorf("Unexpected response from Vendor command. got % x", resp[0:2])
	}
	return success, resp[2:], err
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
	if version[0] != 0x0 {
		return nil, fmt.Errorf("Vendor command failed with error code %x\n", version[0])
	}
	env.version = version[1:]

	if !success {
		return nil, fmt.Errorf("Operation failed")
	}
	if len(version) < 9 {
		card.Disconnect(scard.ResetCard)
		return nil, fmt.Errorf("Got short response from GET_VERISON")
	}

	if !bytes.Equal(version[1:7], SUPPORTED_CARD) {
		card.Disconnect(scard.ResetCard)
		return nil, fmt.Errorf("Unsupported card: % x\n", rspCodeBytes)
	}

	return card, nil
}

func (env *NFCEnvoriment) GetUUID() (string, error) {
	success, body, err := env.transmitAndValidate(env.cardConnection, []byte{0xFF, 0xCA, 0x00, 0x00, 0x00})
	if err != nil {
		return "", err
	}

	if !success {
		return "", fmt.Errorf("Operation failed")
	}

	return fmt.Sprintf("%x", body), nil
}

func (env *NFCEnvoriment) parseNtagVersion(ver byte, ci *CardInfo) (*CardInfo, error) {
	switch ver {
	case 0x0f:
		ci.Memory = 144
		ci.ProductName = "NTAG213"
	case 0x11:
		ci.Memory = 504
		ci.ProductName = "NTAG215"
	case 0x13:
		ci.Memory = 888
		ci.ProductName = "NTAG216"
	default:
		return nil, fmt.Errorf("Unsupported card")
	}
	return ci, nil
}

func (env *NFCEnvoriment) getCardInfo() (*CardInfo, error) {
	ci := new(CardInfo)
	if len(env.version) < 6 {
		return nil, fmt.Errorf("Unsupported card")
	}
	switch env.version[1] { // NXP
	case 0x04:
		ci.Manufacturer = "NXP Semiconductors"
		break
	default:
		return nil, fmt.Errorf("Unsupported card")
	}

	if env.version[2] != 0x04 && env.version[3] != 0x02 && env.version[4] != 0x01 {
		return nil, fmt.Errorf("Unsupported card")
	}

	switch env.version[5] {
	case 0x00:
		{
			var err error
			ci, err = env.parseNtagVersion(env.version[6], ci)
			if err != nil {
				return nil, err
			}
			break
		}
	default:
		return nil, fmt.Errorf("Unsupported card")
	}
	return ci, nil

}

func (env *NFCEnvoriment) SetNTAG21xPassword(password uint32) error {
	ci, err := env.getCardInfo()
	if err != nil {
		fmt.Printf("Failed to get card information: %s\n", err.Error())
		env.Unready()
		return err
	}
	if !strings.HasPrefix(ci.Manufacturer, "NXP") || !strings.HasPrefix(ci.ProductName, "NTAG21") {
		return fmt.Errorf("Only NXP NTAG21x supports password")
	}

	passwordBytes := make([]byte, 4)
	binary.BigEndian.PutUint32(passwordBytes, password)
	var cfgBytes []byte
	var cfgStartPage byte
	switch ci.ProductName {
	case "NTAG213":

		{
			err = env.writePage(0x2b, passwordBytes)
			cfgStartPage = 0x29
		}

	case "NTAG215":
		{
			err = env.writePage(0x85, passwordBytes)
			cfgStartPage = 0x83
		}
	case "NTAG216":
		{
			err = env.writePage(0xe5, passwordBytes)
			cfgStartPage = 0xe3
		}
	default:
		{
			return fmt.Errorf("Unsupported %s", ci.ProductName)
		}
	}
	if err != nil {
		return err
	}
	env.setPage(cfgStartPage)
	cfgBytes, err = env.readBytes(16)
	if err != nil {
		return err
	}
	// Auth to card so we don't lock ourselves out
	err = env.NTAG21xAuth(password)
	if err != nil {
		return err
	}
	// Set starting page for protection
	cfgBytes[3] = STARTING_REGION
	// Set PROT bit to 1 for read and write protection
	cfgBytes[4] = cfgBytes[4] | (0x1 << 7)
	err = env.writePage(cfgStartPage, cfgBytes[0:4])
	if err != nil {
		return err
	}
	err = env.writePage(cfgStartPage+4, cfgBytes[4:8])
	if err != nil {
		return err
	}

	fmt.Printf("cfg bytes: % x", cfgBytes)

	return nil
}

// NTAG21xAuth send the PWD_AUTH command to an NXP NTAG21x
func (env *NFCEnvoriment) NTAG21xAuth(password uint32) error {
	ci, err := env.getCardInfo()
	if err != nil {
		fmt.Printf("Failed to get card information: %s\n", err.Error())
		env.Unready()
		return err
	}
	if !strings.HasPrefix(ci.Manufacturer, "NXP") || !strings.HasPrefix(ci.ProductName, "NTAG21") {
		return fmt.Errorf("Only NXP NTAG21x supports password")
	}
	payload := []byte{0x1b}
	payload = binary.BigEndian.AppendUint32(payload, password)
	success, response, err := env.transmitVendorCommand(env.cardConnection, payload)
	if err != nil {
		return err
	}
	if !success {
		return fmt.Errorf("Operation failed")
	}
	if len(response) < 1 {
		return fmt.Errorf("response too short")
	}
	if response[0] != 0 {
		return fmt.Errorf("Authentication failed")
	}
	fmt.Printf("response: % x\n", response)
	return nil

}

func (env *NFCEnvoriment) readPage(pageNumber byte) ([]byte, error) {
	var opread []byte
	opread = append(opread, OPERATION_READ...)
	opread[3] = pageNumber
	success, body, err := env.transmitAndValidate(env.cardConnection, opread)
	if err != nil {
		return []byte{}, err
	}
	if !success {
		return []byte{}, fmt.Errorf("Operation failed")
	}
	return body, nil
}

func (env *NFCEnvoriment) writePage(pageNumber byte, data []byte) error {
	if len(data) != int(PAGE_SIZE) {
		return fmt.Errorf("Page must be %d bytes", PAGE_SIZE)
	}
	var opwrite []byte
	opwrite = append(opwrite, OPERATION_WRITE...)
	opwrite[3] = pageNumber

	fmt.Printf("[DEBUG] Writing page=%x data=%v\n", pageNumber, data)
	opwrite = append(opwrite, data...) // Append the data to write
	success, _, err := env.transmitAndValidate(env.cardConnection, opwrite)
	if err != nil {
		return err
	}
	if !success {
		return fmt.Errorf("write operation failed")
	}
	return nil
}

func (env *NFCEnvoriment) EndConnection() {
	if env.cardConnection != nil {
		fmt.Printf("Reseted card\n")
		env.cardConnection.Disconnect(scard.ResetCard)
	}
	env.cardConnection = nil
}

func (env *NFCEnvoriment) StartConnection() error {
	index, err := env.waitUntilCardPresent(time.Second * 20)
	if err != nil {
		fmt.Printf("Failed to get card information: %s\n", err.Error())
		env.Unready()
		return err
	}

	// Connect to the card
	card, err := env.connectAndValidateCard(index)
	if err != nil {
		return err
	}
	fmt.Printf("Connected to card\n")
	env.cardConnection = card
	env.buffer = []byte{}
	return nil
}

func (env *NFCEnvoriment) setPage(page byte) {
	env.currentPage = page
}

func (env *NFCEnvoriment) readByte() (byte, error) {
	if len(env.buffer) == 0 {
		var err error
		env.buffer, err = env.readPage(env.currentPage)
		if err != nil {
			return 0x00, err
		}
		if len(env.buffer) > int(PAGE_SIZE) {
			env.buffer = env.buffer[:PAGE_SIZE]
		}
		fmt.Printf("[DEBUG] page read 0x%x data=%v\n", env.currentPage, env.buffer)
		env.currentPage++
	}
	readElement := env.buffer[0]
	env.buffer = env.buffer[1:]
	return readElement, nil
}

func (env *NFCEnvoriment) readBytes(nBytes int) ([]byte, error) {
	var buf []byte
	for i := 0; i < nBytes; i++ {
		b, err := env.readByte()
		if err != nil {
			return buf, err
		}
		buf = append(buf, b)
	}
	return buf, nil
}

func (env *NFCEnvoriment) checkAndTransmit(accumulatedBytes []byte) ([]byte, bool, error) {
	if len(accumulatedBytes) != int(PAGE_SIZE) {
		return accumulatedBytes, false, nil
	}
	err := env.writePage(env.currentPage, accumulatedBytes)
	if err != nil {
		return []byte{}, false, err
	}
	env.currentPage++
	return []byte{}, true, err
}

func (env *NFCEnvoriment) WriteTags(tags []types.Tag) error {
	var err error
	env.setPage(STARTING_REGION)
	env.cardConnection.BeginTransaction()
	//Transmissions must be done in blocks of 16, so here we make sure we're transmitting 16 bytes at the time
	var accumulatedBytes []byte
	for _, tag := range tags {
		fmt.Printf("[DEBUG] Writing tag 0x%x\n", tag.Id)
		accumulatedBytes = append(accumulatedBytes, tag.Id)
		accumulatedBytes, _, err = env.checkAndTransmit(accumulatedBytes)
		if err != nil {
			return err
		}
		fmt.Printf("[DEBUG] Writing tag lenght=%d\n", byte(len(tag.Data)))
		accumulatedBytes = append(accumulatedBytes, byte(len(tag.Data)))
		accumulatedBytes, _, err = env.checkAndTransmit(accumulatedBytes)
		if err != nil {
			return err
		}
		fmt.Printf("[DEBUG] Writing data=%v\n", byte(len(tag.Data)))
		for _, dataByte := range tag.Data {
			accumulatedBytes = append(accumulatedBytes, dataByte)
			accumulatedBytes, _, err = env.checkAndTransmit(accumulatedBytes)
			if err != nil {
				return err
			}
		}
	}
	//If some bytes are remaining in the array, we need to transmit a total of 16 bytes, so we fill it with zeros
	if len(accumulatedBytes) > 0 {
		var transmitted bool
		for {
			accumulatedBytes = append(accumulatedBytes, 0x00)
			accumulatedBytes, transmitted, err = env.checkAndTransmit(accumulatedBytes)
			if err != nil {
				return err
			}
			if transmitted {
				break
			}
		}
	}

	return env.cardConnection.EndTransaction(0)
}

func (env *NFCEnvoriment) ReadTags() ([]types.Tag, error) {

	var tags []types.Tag
	var tagId byte
	var err error
	var tagLenght byte
	var readByte byte
	env.setPage(STARTING_REGION)
	for {
		tagId, err = env.readByte()
		if err != nil {
			return tags, err
		}
		if tagId == 0x00 {
			return tags, nil
		}
		fmt.Printf("[DEBUG] Found tag 0x%x\n", tagId)
		tagLenght, err = env.readByte()
		if err != nil {
			return tags, err
		}
		fmt.Printf("[DEBUG] Tag lenght is %d\n", int(tagLenght))
		if tagLenght == 0x00 {
			return tags, fmt.Errorf("Tag lenght is zero. Probally corrupt data")
		}
		var tagBytes []byte
		for i := 0; i < int(tagLenght); i++ {
			readByte, err = env.readByte()
			if err != nil {
				return tags, err
			}
			tagBytes = append(tagBytes, readByte)
		}
		fmt.Printf("[DEBUG] Tag data is %v\n", tagBytes)
		tags = append(tags, types.Tag{
			Id:   tagId,
			Data: tagBytes,
		})

	}
}
