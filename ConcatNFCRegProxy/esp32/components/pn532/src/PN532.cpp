/**
 * @file     pn532.h
 * @author   D. Braun
 * @license  MIT (see license.txt)
 * This is a PN532 Driver for the ESP32 family and IDF 5.3 for NXP's PN532 NFC/13.56MHz RFID Transceiver.
 * This component is inspired the Adafruit library.
 */

#define CONFIG_MIFAREDEBUG
#define CONFIG_PN532DEBUG

#include <string.h>
#include <hal/uart_types.h>
#include "esp_log.h"
#include "esp_err.h"

#include "PN532.h"

static const char TAG[] = "PN532";

const uint8_t pn532response_firmwarevers[] = {0x00, 0xFF, 0x06, 0xFA, 0xD5, 0x03};

static uint8_t pn532_inListedTag;  // Tg number of inlisted tag.

#define PN532_COMMAND_BUFFER_LEN 64
uint8_t pn532_packetbuffer[PN532_COMMAND_BUFFER_LEN];

PN532::PN532(PN532Interface *Interface) {
    m_Interface = Interface;
}

esp_err_t PN532::pn532_get_firmware_version( uint32_t *fw_version)
{
    esp_err_t err;

    pn532_packetbuffer[0] = PN532_COMMAND_GETFIRMWAREVERSION;

    err = m_Interface->pn532_send_command_wait_ack(pn532_packetbuffer, 1, PN532_WRITE_TIMEOUT);
    if (ESP_OK != err) {
        return err;
    }

#ifdef CONFIG_PN532DEBUG
    ESP_LOGD(TAG, "pn532_get_firmware_version(): Waiting for IRQ/ready");
#endif
    err = m_Interface->pn532_wait_ready(100);
    if (ESP_OK != err) {
#ifdef CONFIG_PN532DEBUG
        ESP_LOGD(TAG, "pn532_get_firmware_version(): Timeout occurred");
#endif
        return err;
    }

    // read data packet
    err = m_Interface->pn532_read_data(pn532_packetbuffer, 12, PN532_READ_TIMEOUT);
    if (ESP_OK != err)
        return err;

    // check some basic stuff
    if (0 != memcmp(pn532_packetbuffer + 1, pn532response_firmwarevers, sizeof(pn532response_firmwarevers))) {
#ifdef CONFIG_PN532DEBUG
        ESP_LOGD(TAG, "pn532_get_firmware_version(): get firmware response invalid!");
#endif
        return ESP_FAIL;
    }

    int offset = 7;
    *fw_version  = pn532_packetbuffer[offset++] << 24;
    *fw_version |= pn532_packetbuffer[offset++] << 16;
    *fw_version |= pn532_packetbuffer[offset++] << 8;
    *fw_version |= pn532_packetbuffer[offset];

    return ESP_OK;
}

esp_err_t PN532::pn532_set_passive_activation_retries( uint8_t maxRetries) {
    pn532_packetbuffer[0] = PN532_COMMAND_RFCONFIGURATION;
    pn532_packetbuffer[1] = 5;    // Config item 5 (MaxRetries)
    pn532_packetbuffer[2] = 0xFF; // MxRtyATR (default = 0xFF)
    pn532_packetbuffer[3] = 0x01; // MxRtyPSL (default = 0x01)
    pn532_packetbuffer[4] = maxRetries;

#ifdef CONFIG_MIFAREDEBUG
    ESP_LOGD(TAG, "pn532_set_passive_activation_retries(): Setting MxRtyPassiveActivation to %d", maxRetries);
#endif

    return m_Interface->pn532_send_command_wait_ack(pn532_packetbuffer, 5, PN532_WRITE_TIMEOUT);
}

esp_err_t PN532::pn532_auto_poll(
                          uint8_t baud_rate_and_card_type,
                          int32_t timeout,
                          uart_port_t uart_port)
{
    pn532_packetbuffer[0] = PN532_COMMAND_INAUTOPOLL;
    pn532_packetbuffer[1] = 0xff; // endless polling
    pn532_packetbuffer[2] = 0x1; // n x 150ms polling units
    pn532_packetbuffer[3] = baud_rate_and_card_type;

    esp_err_t err = m_Interface->pn532_send_command_wait_ack(pn532_packetbuffer, 4, PN532_WRITE_TIMEOUT);
    if (ESP_OK != err) {
#ifdef CONFIG_PN532DEBUG
        ESP_LOGD(TAG, "No card(s) read");
#endif
        return err;
    }

#ifdef CONFIG_PN532DEBUG
    ESP_LOGD(TAG, "Waiting for IRQ (indicates card presence)");
#endif
    err = m_Interface->pn532_wait_ready(timeout, uart_port);
    if (ESP_OK != err) {
#ifdef CONFIG_PN532DEBUG
        ESP_LOGD(TAG, "PN532 not ready, timeout or error occurred");
#endif
        return err;
    }
#ifdef CONFIG_PN532DEBUG
    ESP_LOGD(TAG, "PN532 ready. Reading data packet");
#endif
    err = m_Interface->pn532_read_data(pn532_packetbuffer, 32, timeout, uart_port);
    if (ESP_OK != err)
        return err;
    return ESP_OK;
}

esp_err_t PN532::pn532_reset_card() {
    pn532_packetbuffer[0] = PN532_COMMAND_INATR;
    pn532_packetbuffer[1] = 0x00;

    esp_err_t err = m_Interface->pn532_send_command_wait_ack(pn532_packetbuffer, 2, PN532_WRITE_TIMEOUT);
    if (ESP_OK != err) {
#ifdef CONFIG_PN532DEBUG
        ESP_LOGD(TAG, "No card(s) read");
#endif
        return err;
    }

#ifdef CONFIG_PN532DEBUG
    ESP_LOGD(TAG, "Waiting for IRQ (indicates card presence)");
#endif
    err = m_Interface->pn532_wait_ready(PN532_READ_TIMEOUT);
    if (ESP_OK != err) {
#ifdef CONFIG_PN532DEBUG
        ESP_LOGD(TAG, "PN532 not ready, timeout or error occurred");
#endif
        return err;
    }
#ifdef CONFIG_PN532DEBUG
    ESP_LOGD(TAG, "PN532 ready. Reading data packet");
#endif
    err = m_Interface->pn532_read_data(pn532_packetbuffer, 32, 1000);
    if (ESP_OK != err)
        return err;
    return ESP_OK;
}

esp_err_t PN532::pn532_read_passive_target_id(
                                       uint8_t baud_rate_and_card_type,
                                       uint8_t *uid,
                                       uint8_t *uid_length,
                                       int32_t timeout)
{
    pn532_packetbuffer[0] = PN532_COMMAND_INLISTPASSIVETARGET;
    pn532_packetbuffer[1] = 1; // currently only support one card (PN532 can handle two cards)
    pn532_packetbuffer[2] = baud_rate_and_card_type;

    esp_err_t err = m_Interface->pn532_send_command_wait_ack(pn532_packetbuffer, 3, PN532_WRITE_TIMEOUT);
    if (ESP_OK != err) {
#ifdef CONFIG_PN532DEBUG
        ESP_LOGD(TAG, "No card(s) read");
#endif
        return err;
    }

#ifdef CONFIG_PN532DEBUG
    ESP_LOGD(TAG, "Waiting for IRQ (indicates card presence)");
#endif
    err = m_Interface->pn532_wait_ready(timeout);
    if (ESP_OK != err) {
#ifdef CONFIG_PN532DEBUG
        ESP_LOGD(TAG, "PN532 not ready, timeout or error occurred");
#endif
        return err;
    }
    err = m_Interface->pn532_read_data(pn532_packetbuffer, 32, timeout);
    if (ESP_OK != err)
        return err;

    /* ISO14443A card response should be in the following format:

     byte            Description
     -------------   ------------------------------------------
     b0..6           Frame header and preamble
     b7              Number of tags Found
     b8              Tag Number (only one used in this example)
     b9..10          SENS_RES
     b11             SEL_RES
     b12             NFCID Length
     b13..NFCIDLen   NFCID                                      */

#ifdef CONFIG_MIFAREDEBUG
    ESP_LOGD(TAG, "Found %d tags", pn532_packetbuffer[7]);
#endif
    if (pn532_packetbuffer[7] != 1)
        return ESP_FAIL;

#ifdef CONFIG_MIFAREDEBUG
    uint16_t sens_res = pn532_packetbuffer[9] << 8 | pn532_packetbuffer[10];

    ESP_LOGD(TAG, "ATQA: 0x%.2X", sens_res);
    ESP_LOGD(TAG, "SAK: 0x%.2X", pn532_packetbuffer[11]);
#endif

    /* Card appears to be Mifare Classic */
    *uid_length = pn532_packetbuffer[12];
#ifdef CONFIG_MIFAREDEBUG
    ESP_LOGD(TAG, "UID:");
#endif
    for (uint8_t i = 0; i < pn532_packetbuffer[12]; i++) {
        uid[i] = pn532_packetbuffer[13 + i];
    }
#ifdef CONFIG_MIFAREDEBUG
    ESP_LOG_BUFFER_HEX_LEVEL(TAG, uid, pn532_packetbuffer[12], ESP_LOG_DEBUG);
#endif

    return ESP_OK;
}

esp_err_t PN532::pn532_in_data_exchange(
                                 const uint8_t *send_buffer,
                                 uint8_t send_buffer_length,
                                 uint8_t *response,
                                 uint8_t *response_length) {
    if (send_buffer_length > PN532_COMMAND_BUFFER_LEN - 2) {
#ifdef CONFIG_PN532DEBUG
        ESP_LOGD(TAG, "APDU length too long for packet buffer");
#endif
        return ESP_ERR_INVALID_ARG;
    }

    uint8_t i;
    pn532_packetbuffer[0] = PN532_COMMAND_INDATAEXCHANGE;
    pn532_packetbuffer[1] = pn532_inListedTag;
    for (i = 0; i < send_buffer_length; ++i) {
        pn532_packetbuffer[i + 2] = send_buffer[i];
    }

    esp_err_t err = m_Interface->pn532_send_command_wait_ack(pn532_packetbuffer, send_buffer_length + 2, PN532_WRITE_TIMEOUT);
    if (ESP_OK != err) {
#ifdef CONFIG_PN532DEBUG
        ESP_LOGD(TAG, "Could not send_buffer APDU");
#endif
        return err;
    }

    err = m_Interface->pn532_wait_ready(1000);
    if (ESP_OK != err) {
#ifdef CONFIG_PN532DEBUG
        ESP_LOGD(TAG, "Response never received for APDU ... timeout");
#endif
        return err;
    }

    err = m_Interface->pn532_read_data(pn532_packetbuffer, sizeof(pn532_packetbuffer), PN532_READ_TIMEOUT);
    if (ESP_OK != err)
        return err;

    if (pn532_packetbuffer[0] == 0 && pn532_packetbuffer[1] == 0 && pn532_packetbuffer[2] == 0xff) {
        uint8_t length = pn532_packetbuffer[3];
        if (0 != ((pn532_packetbuffer[4] + length) & 0xFF)) {
#ifdef CONFIG_PN532DEBUG
            ESP_LOGD(TAG, "Length check invalid 0x%02X 0x%02X", length, pn532_packetbuffer[4]);

#endif
            return ESP_FAIL;
        }
        if (pn532_packetbuffer[5] == PN532_PN532TOHOST && pn532_packetbuffer[6] == PN532_RESPONSE_INDATAEXCHANGE) {
            if ((pn532_packetbuffer[7] & 0x3f) != 0) {
#ifdef CONFIG_PN532DEBUG
                ESP_LOGD(TAG, "Status code indicates an error");
#endif
                return ESP_FAIL;
            }

            length -= 3;

            if (length > *response_length) {
                length = *response_length; // silent truncation...
            }

            for (i = 0; i < length; ++i) {
                response[i] = pn532_packetbuffer[8 + i];
            }
            *response_length = length;

            return ESP_OK;
        } else {
            ESP_LOGD(TAG, "Don't know how to handle this command: 0x%.2X", pn532_packetbuffer[6]);
            return ESP_FAIL;
        }
    } else {
        ESP_LOGD(TAG, "Preamble missing");
        return ESP_FAIL;
    }
}

esp_err_t PN532::pn532_in_list_passive_target() {
    pn532_packetbuffer[0] = PN532_COMMAND_INLISTPASSIVETARGET;
    pn532_packetbuffer[1] = 1;
    pn532_packetbuffer[2] = 0;

#ifdef CONFIG_PN532DEBUG
    ESP_LOGD(TAG, "About to inList passive target");
#endif

    esp_err_t err = m_Interface->pn532_send_command_wait_ack(pn532_packetbuffer, 3, PN532_WRITE_TIMEOUT);
    if (ESP_OK != err) {
#ifdef CONFIG_PN532DEBUG
        ESP_LOGD(TAG, "Could not send inlistPassiveTarget message");
#endif
        return err;
    }

    err = m_Interface->pn532_wait_ready(10000);
    if (ESP_OK != err)
        return err;

    err = m_Interface->pn532_read_data(pn532_packetbuffer, sizeof(pn532_packetbuffer), PN532_READ_TIMEOUT);
    if (ESP_OK != err)
        return err;

    if (pn532_packetbuffer[0] == 0 && pn532_packetbuffer[1] == 0 && pn532_packetbuffer[2] == 0xff)
    {
        uint8_t length = pn532_packetbuffer[3];
        if (0 != ((pn532_packetbuffer[4] + length) & 0xFF))
        {
#ifdef CONFIG_PN532DEBUG
            ESP_LOGD(TAG, "Length check invalid 0x%.2X 0x%.2X", length, pn532_packetbuffer[4]);
#endif
            return ESP_FAIL;
        }
        if (pn532_packetbuffer[5] == PN532_PN532TOHOST && pn532_packetbuffer[6] == PN532_RESPONSE_INLISTPASSIVETARGET)
        {
            if (pn532_packetbuffer[7] != 1) {
#ifdef CONFIG_PN532DEBUG
                ESP_LOGD(TAG, "Unhandled number of targets inlisted");
#endif
                ESP_LOGI(TAG, "Number of tags inListed: %d", pn532_packetbuffer[7]);
                return ESP_FAIL;
            }

            pn532_inListedTag = pn532_packetbuffer[8];
            ESP_LOGI(TAG, "inList tag %d", pn532_inListedTag);

            return ESP_OK;
        } else {
#ifdef CONFIG_PN532DEBUG
            ESP_LOGD(TAG, "Unexpected response to inlist passive host");
#endif
            return ESP_FAIL;
        }
    }

#ifdef CONFIG_PN532DEBUG
    ESP_LOGD(TAG, "Preamble missing");
#endif
    return ESP_FAIL;
}

esp_err_t PN532::ntag2xx_get_model( NTAG2XX_INFO *model)
{
    if (model == NULL) {
        return ESP_ERR_INVALID_ARG;
    }

    model->model = NTAG2XX_UNKNOWN;

    uint8_t page_mem[16];
    esp_err_t err = ntag2xx_read_page(0, page_mem, sizeof(page_mem));
    if (err != ESP_OK)
        return err;

    int raw_capacity = page_mem[14] * 8;
    ESP_LOGD(TAG, "raw capacity: %d bytes", raw_capacity);

    switch (page_mem[14]) {
        case 0x12:
            model->model = NTAG2XX_NTAG213;
            model->passwordPage = 0x2B;
            model->configPage = 0x29;
            model->auth0Page = 0x2A;
            break;

        case 0x3e:
            model->model = NTAG2XX_NTAG215;
            model->passwordPage = 0x85;
            model->configPage = 0x83;
            model->auth0Page = 0x84;
            break;

        case 0x6d:
            model->model = NTAG2XX_NTAG216;
            model->passwordPage = 0xE5;
            model->configPage = 0xE3;
            model->auth0Page = 0xE4;
            break;

        default:
            model->model = NTAG2XX_UNKNOWN;
    }

    return ESP_OK;
}

/*esp_err_t PN532::ntag2xx_authenticate( uint8_t page, uint8_t *key, uint8_t *uid, uint8_t uid_length) {
    pn532_packetbuffer[0] = PN532_COMMAND_INDATAEXCHANGE;
    pn532_packetbuffer[1] = 1;
    pn532_packetbuffer[2] = MIFARE_CMD_AUTH_A;
    pn532_packetbuffer[3] = page;

    memcpy(&pn532_packetbuffer[4], key, 6);
    if (uid_length > 10) {
        uid_length = 10;
    }
    memcpy(&pn532_packetbuffer[10], uid, uid_length);

    esp_err_t err = m_Interface->pn532_send_command_wait_ack(pn532_packetbuffer, 10 + uid_length, PN532_WRITE_TIMEOUT);

    return err;
}*/

esp_err_t PN532::ntag2xx_clear_password() {
    NTAG2XX_INFO model;
    esp_err_t err = ntag2xx_get_model(&model);
    if (err != ESP_OK) {
        ESP_LOGE(TAG, "Failed to get NTAG model");
        return err;
    }

    if (model.model == NTAG2XX_UNKNOWN) {
        ESP_LOGE(TAG, "Unsupported NTAG model");
        return ESP_ERR_NOT_SUPPORTED;
    }

    // Write blank password (0xFFFFFFFF) to password page
    uint8_t blankPassword[4] = {0xFF, 0xFF, 0xFF, 0xFF};
    err = ntag2xx_write_page(model.passwordPage, blankPassword);
    if (err != ESP_OK) {
        ESP_LOGE(TAG, "Failed to clear password bytes (page 0x%02X)", model.passwordPage);
        return err;
    }

    // Read current configuration
    uint8_t configData[16]; // We need 2 pages (8 bytes)
    err = ntag2xx_read_page(model.configPage, configData, sizeof(configData));
    if (err != ESP_OK) {
        ESP_LOGE(TAG, "Failed to read config pages");
        return err;
    }

    // Set starting page for protection to 0xFF (beyond end of device)
    configData[3] = 0xFF;
    // Clear PROT bit (bit 7 of byte 4)
    configData[4] &= 0x7F;

    // Write back modified configuration
    err = ntag2xx_write_page(model.configPage, configData);
    if (err != ESP_OK) {
        ESP_LOGE(TAG, "Failed to write config page 0");
        return err;
    }

    err = ntag2xx_write_page(model.configPage + 1, configData + 4);
    if (err != ESP_OK) {
        ESP_LOGE(TAG, "Failed to write config page 1");
        return err;
    }

    ESP_LOGI(TAG, "Password protection cleared successfully");
    return ESP_OK;
}

esp_err_t PN532::ntag2xx_set_password(uint32_t password) {
    // First authenticate with the tag (if already protected)
    uint8_t passwordBytes[4];
    passwordBytes[0] = (password >> 24) & 0xFF;
    passwordBytes[1] = (password >> 16) & 0xFF;
    passwordBytes[2] = (password >> 8) & 0xFF;
    passwordBytes[3] = password & 0xFF;

    // Determine tag model
    
    NTAG2XX_INFO model;
    esp_err_t err = ntag2xx_get_model(&model);
    if (err != ESP_OK) {
        ESP_LOGE(TAG, "Failed to get NTAG model");
        return err;
    }

    if (model.model == NTAG2XX_UNKNOWN) {
        ESP_LOGE(TAG, "Unsupported NTAG model");
        return ESP_ERR_NOT_SUPPORTED;
    }

    // Write password to password page
    err = ntag2xx_write_page(model.passwordPage, passwordBytes);
    if (err != ESP_OK) {
        ESP_LOGE(TAG, "Failed to write password to page 0x%02X", model.passwordPage);
        return err;
    }

    err = pn532_reset_card();
    if (err != ESP_OK) {
        ESP_LOGE(TAG, "Failed to reset card");
        return err;
    }
    err = ntag2xx_authenticate(passwordBytes);
    if (err != ESP_OK) {
        ESP_LOGE(TAG, "Failed to authenticate");
        return err;
    }

    // Read current configuration
    uint8_t configData[8]; // We only need 2 pages (8 bytes)
    err = ntag2xx_read_page(model.configPage, configData, sizeof(configData));
    if (err != ESP_OK) {
        ESP_LOGE(TAG, "Failed to read config pages");
        return err;
    }

    // Start page to protect
    configData[3] = 0x10;
    // Update configuration (set PROT bit)
    configData[4] |= (1 << 7); // Set bit 7 of byte 4 (PROT bit)

    // Write back modified configuration
    err = ntag2xx_write_page(model.configPage, configData);
    if (err != ESP_OK) {
        ESP_LOGE(TAG, "Failed to write config page 0");
        return err;
    }

    err = ntag2xx_write_page(model.configPage + 1, configData + 4);
    if (err != ESP_OK) {
        ESP_LOGE(TAG, "Failed to write config page 1");
        return err;
    }

    ESP_LOGI(TAG, "Password protection set successfully");
    return ESP_OK;
}

esp_err_t PN532::ntag2xx_authenticate(uint8_t *password) {
    pn532_packetbuffer[0] = PN532_COMMAND_INCOMMUNICATETHRU;
    pn532_packetbuffer[1] = NTAG_CMD_PWD_AUTH;

    memcpy(&pn532_packetbuffer[2], password, 4);

    esp_err_t err = m_Interface->pn532_send_command_wait_ack(pn532_packetbuffer, 6, PN532_WRITE_TIMEOUT);

    if (err != ESP_OK) {
        return err;
    }
    ESP_LOGI(TAG, "Reading Auth response");

    uint8_t response[16];
    err = m_Interface->pn532_read_data(response, 11, PN532_READ_TIMEOUT);
    if (err != ESP_OK) {
        ESP_LOGE(TAG, "Failed to read response: 0x%02x", err);
        return err;
    }
    if (response[5] != 0xd5 || response[6] != 0x43) {
        ESP_LOGE(TAG, "Unexpected response: 0x%02x 0x%02x", response[5], response[6]);
        return ESP_FAIL;
    }
    if (response[7] != 0x00) {
        ESP_LOGE(TAG, "Authenitcation failed: 0x%02x", response[7]);
        return ESP_FAIL;
    }
    ESP_LOGI(TAG, "Authentication successful");
    return ESP_OK;
}

esp_err_t PN532::ntag2xx_read_page( uint8_t page, uint8_t *buffer, size_t read_len)
{
    // TAG Type       PAGES   USER START    USER STOP
    // --------       -----   ----------    ---------
    // NTAG 203       42      4             39
    // NTAG 213       45      4             39
    // NTAG 215       135     4             129
    // NTAG 216       231     4             225

    if (page >= 231 || read_len == 0) {
#ifdef CONFIG_MIFAREDEBUG
        ESP_LOGD(TAG, "Page value out of range");
#endif
        return ESP_ERR_INVALID_ARG;
    }

    if (read_len > 16)
        read_len = 16;

#ifdef CONFIG_MIFAREDEBUG
    ESP_LOGD(TAG, "Reading page %d", page);
#endif

    /* Prepare the command */
    pn532_packetbuffer[0] = PN532_COMMAND_INDATAEXCHANGE;
    pn532_packetbuffer[1] = 1; /* Card number */
    pn532_packetbuffer[2] = MIFARE_CMD_READ; /* Mifare Read command = 0x30 */
    pn532_packetbuffer[3] = page; /* Page Number (0..63 in most cases) */

    /* Send the command */
    esp_err_t err = m_Interface->pn532_send_command_wait_ack(pn532_packetbuffer, 4, PN532_WRITE_TIMEOUT);
    if (err != ESP_OK) {
#ifdef CONFIG_MIFAREDEBUG
        ESP_LOGD(TAG, "write failed or ACK not received for command");
#endif
        return err;
    }

#ifdef CONFIG_PN532DEBUG
    ESP_LOGD(TAG, "ntag2xx_ReadPage(): Waiting for IRQ/ready");
#endif
    err = m_Interface->pn532_wait_ready(100);
    if (ESP_OK != err) {
#ifdef CONFIG_PN532DEBUG
        ESP_LOGD(TAG, "ntag2xx_ReadPage(): Timeout occurred");
#endif
        return err;
    }

    /* Read the response packet */
    err = m_Interface->pn532_read_data(pn532_packetbuffer, 26, PN532_READ_TIMEOUT);
    if (err != ESP_OK)
        return err;

#ifdef CONFIG_MIFAREDEBUG
    ESP_LOGD(TAG, "Received: ");
    ESP_LOG_BUFFER_HEX_LEVEL(TAG, pn532_packetbuffer, 26, ESP_LOG_DEBUG);
#endif

    uint8_t status = pn532_packetbuffer[7];
    // check error code of status byte
    if ((status & 0x3F) == 0x00) {
        memcpy(buffer, pn532_packetbuffer + 8, read_len);
    }
    else {
#ifdef CONFIG_MIFAREDEBUG
        ESP_LOGD(TAG, "Status byte indicates an error: 0x%02x", pn532_packetbuffer[7]);
#endif
        return ESP_FAIL;
    }

    /* Display data for debug if requested */
#ifdef CONFIG_MIFAREDEBUG
    ESP_LOGD(TAG, "Page %d", page);
    ESP_LOG_BUFFER_HEX_LEVEL(TAG, buffer, 4, ESP_LOG_DEBUG);
#endif

    // Return OK signal
    return ESP_OK;
}

esp_err_t PN532::ntag2xx_write_page( uint8_t page, const uint8_t * data)
{
    // TAG Type       PAGES   USER START    USER STOP
    // --------       -----   ----------    ---------
    // NTAG 203       42      4             39
    // NTAG 213       45      4             39
    // NTAG 215       135     4             129
    // NTAG 216       231     4             225

    if ((page < 4) || (page > 231)) {
#ifdef CONFIG_MIFAREDEBUG
        ESP_LOGD(TAG, "Page value out of range");
#endif
        // Return Failed Signal
        return ESP_ERR_INVALID_ARG;
    }

#ifdef CONFIG_MIFAREDEBUG
    ESP_LOGD(TAG, "Trying to write 4 byte page %d", page);
#endif

    /* Prepare the first command */
    pn532_packetbuffer[0] = PN532_COMMAND_INDATAEXCHANGE;
    pn532_packetbuffer[1] = 1; /* Card number */
    pn532_packetbuffer[2] = MIFARE_ULTRALIGHT_CMD_WRITE; /* Mifare Ultralight Write command = 0xA2 */
    pn532_packetbuffer[3] = page; /* Page Number (0..63 for most cases) */
    memcpy(pn532_packetbuffer + 4, data, 4); /* Data Payload */

    /* Send the command */
    esp_err_t err = m_Interface->pn532_send_command_wait_ack(pn532_packetbuffer, 8, PN532_WRITE_TIMEOUT);
    if (err != ESP_OK) {
#ifdef CONFIG_MIFAREDEBUG
        ESP_LOGD(TAG, "Failed to receive ACK for write command");
#endif
        return err;
    }

#ifdef CONFIG_PN532DEBUG
    ESP_LOGD(TAG, "ntag2xx_WritePage(): Waiting for IRQ/ready");
#endif
    err = m_Interface->pn532_wait_ready(100);
    if (ESP_OK != err) {
#ifdef CONFIG_PN532DEBUG
        ESP_LOGD(TAG, "ntag2xx_WritePage(): Timeout occurred");
#endif
        return err;
    }

    /* Read the response packet */
    err = m_Interface->pn532_read_data(pn532_packetbuffer, 26, PN532_READ_TIMEOUT);
    return err;
}

// Function to convert a 32-bit unsigned integer to big-endian byte array
void uint32_to_big_endian_bytes(uint32_t value, uint8_t* buffer) {
    buffer[0] = (value >> 24) & 0xFF; // Most significant byte
    buffer[1] = (value >> 16) & 0xFF;
    buffer[2] = (value >> 8) & 0xFF;
    buffer[3] = value & 0xFF;         // Least significant byte
}

