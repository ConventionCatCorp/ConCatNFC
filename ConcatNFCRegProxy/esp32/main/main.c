#define CONFIG_LOG_MASTER_LEVEL ESP_LOG_DEBUG

#include <stdio.h>
#include <stdbool.h>
#include <soc/gpio_num.h>
#include <esp_log.h>
#include "driver/spi_common.h"

#include "pn532.h"
#include "pn532_driver_hsu.h"

#define TAG "main"

static pn532_io_t nfc;
esp_err_t err;

bool setup(void) {
    ESP_LOGI(TAG, "init PN532 in HSU mode");
    ESP_ERROR_CHECK(pn532_new_driver_hsu(GPIO_NUM_9,
                                         GPIO_NUM_10,
                                         -1,
                                         -1,
                                         UART_NUM_1,
                                         115200,
                                         &nfc));
    do {
        err = pn532_init(&nfc);
        if (err != ESP_OK) {
            ESP_LOGW(TAG, "failed to initialize PN532");
            pn532_release(&nfc);
            vTaskDelay(1000 / portTICK_PERIOD_MS);
        }
    } while(err != ESP_OK);
    printf("init_PN532_SPI success\n");
    return true;
}

bool getFirmwareVersion(void) {
    uint32_t version_data = 0;
    do {
        err = pn532_get_firmware_version(&nfc, &version_data);
        if (ESP_OK != err) {
            ESP_LOGI(TAG, "Didn't find PN53x board");
            pn532_reset(&nfc);
            vTaskDelay(1000 / portTICK_PERIOD_MS);
        }
    } while (ESP_OK != err);
    // Got ok data, print it out!
    printf("Found chip PN5%02x\n",(uint8_t ) (version_data>>24) & 0xFF);
    printf("Firmware ver. %d", (uint8_t) (version_data>>16) & 0xFF);
    printf(".%d\n", (uint8_t) (version_data>>8) & 0xFF);
    return true;
}

void print_hex(uint8_t *buffer, uint8_t separator, uint8_t buffer_len) {
    for (int i = 0; i < buffer_len; i++) {
        printf("%02x", buffer[i]);
        if (separator && i < buffer_len - 1) {
            printf("%c", separator);
        }
    }
}

void app_main(void) {
    uint8_t uid[] = { 0, 0, 0, 0, 0, 0, 0 };  // Buffer to store the returned UID
    uint8_t uidLength;                        // Length of the UID (4 or 7 bytes depending on ISO14443A card type)

    if (!setup()) {
        printf("setup failed\n");
        return;
    }
/*    EnableIRQ();
*//*
    if (!EnableIRQ()) {
        printf("EnableIRQ failed\n");
        return;
    }
*//*
    if (!SAMConfig()) {
        printf("SAMConfig failed\n");
        return;
    }*/
    if (!getFirmwareVersion()) {
        printf("getFirmwareVersion failed\n");
        return;
    }

    while (1) {
        printf("Waiting for an ISO14443A Card ...");
        // Wait for an ISO14443A type cards (Mifare, etc.).  When one is found
        // 'uid' will be populated with the UID, and uidLength will indicate
        // if the uid is 4 bytes (Mifare Classic) or 7 bytes (Mifare Ultralight)
//        AutoPoll(PN532_MIFARE_ISO14443A);
/*
        while (1) {
            success = AutoPoll(PN532_MIFARE_ISO14443A);
            if (success) {
                break;
            }
        }
*/
        printf("Reading Card...");

        err = pn532_read_passive_target_id(&nfc, PN532_BRTY_ISO14443A_106KBPS, uid, &uidLength, 0);

        if (err == ESP_OK) {
            // Display some basic information about the card
            printf("Found an ISO14443A card\n");
            printf("  UID Length: %d bytes\n", uidLength);
            printf("  UID Value: ");
            print_hex(uid, ':', uidLength);
            printf("\n");

            if (uidLength == 4)
            {
                // We probably have a Mifare Classic card ...
                uint32_t cardid = uid[0];
                cardid <<= 8;
                cardid |= uid[1];
                cardid <<= 8;
                cardid |= uid[2];
                cardid <<= 8;
                cardid |= uid[3];
                printf("Seems to be a Mifare Classic card #");
                printf("%08lx", cardid);
            }
            printf("\n");
        } else {
            printf("Error\n");
        }
        vTaskDelay(500 / portTICK_PERIOD_MS);
    }
}