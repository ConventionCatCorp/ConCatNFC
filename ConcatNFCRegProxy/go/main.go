package main

import (
	"bufio"
	"bytes"
	"errors"
	"fmt"
	"log"
	"os"
	"strconv"
	"strings"

	"github.com/ebfe/scard"
)

func waitUntilCardPresent(ctx *scard.Context, readers []string) (int, error) {
	rs := make([]scard.ReaderState, len(readers))
	for i := range rs {
		rs[i].Reader = readers[i]
		rs[i].CurrentState = scard.StateUnaware
	}

	for {
		for i := range rs {
			if rs[i].EventState&scard.StatePresent != 0 {
				return i, nil
			}
			rs[i].CurrentState = rs[i].EventState
		}
		err := ctx.GetStatusChange(rs, -1)
		if err != nil {
			return -1, err
		}
	}
}

func waitUntilCardRelease(ctx *scard.Context, readers []string, index int) error {
	rs := make([]scard.ReaderState, 1)

	rs[0].Reader = readers[index]
	rs[0].CurrentState = scard.StatePresent

	for {

		if rs[0].EventState&scard.StateEmpty != 0 {
			return nil
		}
		rs[0].CurrentState = rs[0].EventState

		err := ctx.GetStatusChange(rs, -1)
		if err != nil {
			return err
		}
	}
}

func main() {
	ctx, err := scard.EstablishContext()
	if err != nil {
		log.Fatal("Cannot establish connection to scard: %u", err)
		return
	}
	defer ctx.Release()
	//List available readers
	readers, err := ctx.ListReaders()
	if err != nil {
		log.Fatal("cannot list readers: %u", err)
		return
	}

	if len(readers) < 1 {
		log.Fatal(errors.New("Devices not found. Try to plug-in new device and restart"))
	}

	fmt.Printf("Found %d device:\n", len(readers))
	for i, reader := range readers {
		fmt.Printf("[%d] %s\n", i+1, reader)
	}
	var nDevice int
	//Device should be selected by user input
	for {
		fmt.Print("Enter device number to start: ")
		inputReader := bufio.NewReader(os.Stdin)
		deviceStr, _ := inputReader.ReadString('\n')

		deviceStr = strings.Replace(deviceStr, "\r\n", "", -1)
		deviceStr = strings.Replace(deviceStr, "\n", "", -1)
		deviceInt, err := strconv.Atoi(deviceStr)
		if err != nil {
			fmt.Println("Please input integer value")
			continue
		}
		if deviceInt < 0 {
			fmt.Println("Please input positive integer value")
			continue
		}
		if deviceInt > len(readers) {
			fmt.Printf("Value should be less than or equal to %d\n", len(readers))
			continue
		}
		nDevice = deviceInt
		break
	}

	fmt.Println("Selected device:")
	fmt.Printf("[%d] %s\n", nDevice, readers[nDevice-1])
	selectedReaders := []string{readers[nDevice-1]}

	for {
		fmt.Println("Waiting for a Card")
		index, err := waitUntilCardPresent(ctx, selectedReaders)
		if err != nil {
			log.Fatal(err)
		}

		//Connect to card
		fmt.Println("Connecting to card...", selectedReaders)

		card, err := ctx.Connect(selectedReaders[index], scard.ShareShared, scard.ProtocolAny)
		if err != nil {
			fmt.Println("Connecting to card...")
			log.Fatal(err)
		}

		defer card.Disconnect(scard.ResetCard)

		status, err := card.Status()
		if err != nil {
			log.Fatal(err)
		}
		if len(status.Atr) < 15 {
			card.Disconnect(scard.ResetCard)
			log.Fatal("Card ATR is too short")
		}
		supportedCard := []byte{0x3B, 0x8F, 0x80, 0x1, 0x80, 0x4F, 0xC, 0xA0, 0x0, 0x0, 0x3, 0x6, 0x3, 0x0, 0x3}
		// Need to check for MIFARE Ultralight
		rspCodeBytes := status.Atr[:15]
		if !bytes.Equal(rspCodeBytes, supportedCard) {
			card.Disconnect(scard.ResetCard)
			log.Fatal("Operation failed to complete. Error code % x\n", rspCodeBytes)
		}
		//GET DATA command
		var cmd = []byte{0xFF, 0xCA, 0x00, 0x00, 0x00}

		rsp, err := card.Transmit(cmd)
		if err != nil {
			log.Fatal(err)
		}

		if len(rsp) < 2 {
			card.Disconnect(scard.ResetCard)
			log.Fatal("Not enough bytes in answer. Try again")
		}

		//Check response code - two last bytes of response
		rspCodeBytes = rsp[len(rsp)-2:]
		successResponseCode := []byte{0x90, 0x00}
		if !bytes.Equal(rspCodeBytes, successResponseCode) {

			card.Disconnect(scard.ResetCard)
			log.Fatal("Operation failed to complete. Error code % x\n", rspCodeBytes)
		}

		uidBytes := rsp[0 : len(rsp)-2]
		fmt.Printf("UID is: % x\n", uidBytes)
		/*		fmt.Printf("Writting as keyboard input...")
				err = string2keyboard.KeyboardWrite(s.formatOutput(uidBytes))
				if err != nil {
					fmt.Printf("Could write as keyboard output. Error: %s\n", err.Error())
				} else {
					fmt.Printf("Success!\n")
				}*/

		// Send GET_VERSION
		cmd = []byte{0xff, 0x00, 0x00, 0x00, 0x3, 0xd4, 0x42, 0x60}

		rsp, err = card.Transmit(cmd)
		if err != nil {
			log.Fatal(err)
		}

		if len(rsp) < 2 {
			card.Disconnect(scard.ResetCard)
			log.Fatal("Not enough bytes in answer. Try again")
		}

		//Check response code - two last bytes of response
		rspCodeBytes = rsp[len(rsp)-2:]
		successResponseCode = []byte{0x90, 0x00}
		if !bytes.Equal(rspCodeBytes, successResponseCode) {
			card.Disconnect(scard.ResetCard)
			log.Fatal("Operation failed to complete. Error code % x\n", rspCodeBytes)
		}
		if len(rsp) < 13 {
			card.Disconnect(scard.ResetCard)
			log.Fatal("Got short response from GET_VERISON")
		}

		supportedCard = []byte{0x00, 0x04, 0x04, 0x02, 0x01, 0x00}
		// Need to check for NTAG21x
		rspCodeBytes = rsp[3:9]
		if !bytes.Equal(rspCodeBytes, supportedCard) {
			card.Disconnect(scard.ResetCard)
			log.Fatal("Unsupported card: % x\n", rspCodeBytes)
		}
		var memSize int
		switch rsp[9] {
		case 0xf:
			// NTAG213
			memSize = 144
		case 0x11:
			// NTAG215
			memSize = 504
		case 0x13:
			memSize = 888
		default:
			log.Fatal("Unknown NTAG21x mem size: %x\n", rsp[9])
		}

		fmt.Printf("NTAG21x with memory : %d\n", memSize)

		card.Disconnect(scard.ResetCard)

		//Wait while card will be released
		fmt.Print("Waiting for card release...")
		err = waitUntilCardRelease(ctx, selectedReaders, index)
		fmt.Println("Card released")

	}
}
