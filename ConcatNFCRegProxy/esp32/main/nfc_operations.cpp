#include "nfc_operations.h"
#include "ConCatTag.h"
#include <esp_log.h>

#define TAG "main"


esp_err_t get_uuid(PN532 *nfc, uint8_t *uuid, uint8_t *uidLength, int32_t timeoutMs) {
    ESP_LOGD(TAG, "Waiting for an ISO14443A Card...");

    // Wait for an ISO14443A type cards (Mifare, etc.) with a timeout.
    if (timeoutMs == -1){
        timeoutMs = 1000;
    }
    return nfc->pn532_read_passive_target_id(PN532_BRTY_ISO14443A_106KBPS, uuid, uidLength, timeoutMs);
}

esp_err_t set_nfc_password(PN532 *nfc, uint32_t pwd) {
    ESP_LOGD(TAG, "Writing password on card");
    esp_err_t err = nfc->ntag2xx_set_password(pwd);
    if (err != ESP_OK) {
        ESP_LOGE(TAG, "Error setting password: %s", esp_err_to_name(err));
        return err;
    }
    return ESP_OK;
}

esp_err_t clear_nfc_password(PN532 *nfc, uint32_t pwd) {
 
    uint8_t passwordBytes[4];
    uint32_to_big_endian_bytes(pwd, passwordBytes);
    esp_err_t err = nfc->ntag2xx_authenticate(passwordBytes);
     if (err != ESP_OK) {
        ESP_LOGE(TAG, "Error authenticating for password clear: %s", esp_err_to_name(err));
        return err;
    }
    ESP_LOGD(TAG, "Clearing password on card");
    err = nfc->ntag2xx_clear_password();
    if (err != ESP_OK) {
        ESP_LOGE(TAG, "Error clearing password: %s", esp_err_to_name(err));
        return err;
    }
    return ESP_OK;
}

bool is_valid_tag(ConCatTag *tags) {
    ESP_LOGD(TAG, "Get tag type");
    return tags->IsTagModelValid();
}

returnData write_on_card(TagArray tagsNew, ConCatTag *tags, uint8_t expectedUUID[], uint8_t expectedUUIDLength, uint32_t *password) {
    //Just so we can test the writing.
    returnData ret;
    memset(&ret, 0, sizeof(returnData));
    
    uint8_t uid[] = { 0, 0, 0, 0, 0, 0, 0 };  // Buffer to store the returned UID
    uint8_t uidLength;  
    esp_err_t err = get_uuid(tags->nfc, uid, &uidLength);
    if (err != ESP_OK) {
        ret.errorCode = err;
        ret.success = false;
        return ret;
    }
    if (expectedUUIDLength != uidLength || memcmp(expectedUUID, uid, uidLength) != 0) {
        ret.success = false;
        ret.message = (char*)"UUID mismatch";
        return ret;
    }
    if (!is_valid_tag(tags)){
        ret.success = false;
        ret.message = (char*)"Only NXP NTAG21x supports password";
        return ret;
    }
    if (password != NULL) {
        ret.success = tags->unlockTag(*password);
        if (!ret.success) {
            ret.message = (char*)"Unlock failed";
            return ret;
        }
    }

    if (!tags->writeTags(tagsNew)){
        ret.success = false;
        ret.message = (char*)"oh shit, failed to write :(";
        return ret;
    }
    auto tagJson = tagsNew.toStruct().toJSON();
    ret.message = tagJson;
    ret.success = true;
    ret.freeMessage = true;
    return ret;
}

returnData format_card(ConCatTag *tags, uint8_t expectedUUID[], uint8_t expectedUUIDLength, uint32_t *password) {
    returnData ret;
    memset(&ret, 0, sizeof(returnData));

    uint8_t uid[] = { 0, 0, 0, 0, 0, 0, 0 };  // Buffer to store the returned UID
    uint8_t uidLength;
    esp_err_t err = get_uuid(tags->nfc, uid, &uidLength);
    if (err != ESP_OK) {
        ret.errorCode = err;
        ret.success = false;
        return ret;
    }
    if (expectedUUIDLength != uidLength || memcmp(expectedUUID, uid, uidLength) != 0) {
        ret.success = false;
        ret.message = (char*)"UUID mismatch";
        return ret;
    }
    if (!is_valid_tag(tags)){
        ret.success = false;
        ret.message = (char*)"Only NXP NTAG21x supports password";
        return ret;
    }
    if (password != NULL) {
        ret.success = tags->unlockTag(*password);
        if (!ret.success) {
            ret.message = (char*)"Unlock failed";
            return ret;
        }
    }
    if (!tags->format()){
        ret.success = false;
        ret.message = (char*)"oh shit, failed to write :(";
        return ret;
    }
    ret.message = "Success";
    ret.success = true;
    return ret;
}

returnData calibrate_rf_field(ConCatTag *tags, uint8_t startingFileStrength) {
    returnData ret;
    memset(&ret, 0, sizeof(returnData));

    uint8_t data[16];

    //tags->nfc->ntag2xx_read_page(0, data, 16);

    uint8_t currentStrength = startingFileStrength;

    ESP_LOGI(TAG, "Calibrating: Setting initial field strength to: %d", currentStrength);

    esp_err_t err = tags->nfc->pn532_set_rf_field_strength(currentStrength);
    if (err != ESP_OK) {
        ret.message = "Failed to set RF field strength";
        ret.success = false;
        return ret;
    }
    uint8_t uid[] = { 0, 0, 0, 0, 0, 0, 0 };  // Buffer to store the returned UID
    uint8_t uidLength;

    // Delay to let field adjust
    vTaskDelay(100 / portTICK_PERIOD_MS);

    for (;;) {
        bool restart = false;
        for (int i = 0; i < 500; i++) {
            err = ESP_OK;
            if (i == 0) {
                err = get_uuid(tags->nfc, uid, &uidLength, 200);
            }
            if (err == ESP_OK) {
                ESP_LOGD(TAG, "Reading page 0 iteration: %d", i);
                err = tags->nfc->ntag2xx_read_page(0, data, 16);
            }
            if (err != ESP_OK) {
                currentStrength -= 5;
                ESP_LOGI(TAG, "Failed to read - decreasing to: %d", currentStrength);
                if (currentStrength < 10) {
                    ret.message = "Failed to calibrate RF field strength";
                    ret.success = false;
                    return ret;
                }
                err = tags->nfc->pn532_set_rf_field_strength(currentStrength);
                if (err != ESP_OK) {
                    ret.message = "Failed to set RF field strength";
                    ret.success = false;
                    return ret;
                }
                // Delay to let field adjust
                vTaskDelay(100 / portTICK_PERIOD_MS);
                restart = true;
                break;
            }
            ESP_LOGD(TAG, "Reading page 0 done");
        }
        if (!restart) {
            break;
        }
    }

    char *message = (char *) malloc(64);

    sprintf(message, "Success - Calibrated strength = %d", currentStrength);
    ret.message = message;
    ret.success = true;
    ret.freeMessage = true;
    ret.u8_data = currentStrength;
    return ret;
}

returnData read_tag_data(CardDefinition &tagsRead, ConCatTag *tags, uint8_t expectedUUID[], uint8_t expectedUUIDLength, uint32_t *password) {
    returnData ret;
    memset(&ret, 0, sizeof(returnData));

    uint8_t uid[] = { 0, 0, 0, 0, 0, 0, 0 };  // Buffer to store the returned UID
    uint8_t uidLength;                        // Length of the UID (4 or 7 bytes depending on ISO14443A card type)

    esp_err_t err = get_uuid(tags->nfc, uid, &uidLength);
    if (err != ESP_OK) {
        ret.errorCode = err;
        ret.success = false;
        return ret;
    }
    if (expectedUUIDLength != uidLength || memcmp(expectedUUID, uid, uidLength) != 0) {
        ret.success = false;
        ret.message = (char*)"UUID mismatch";
        return ret;
    }
    if (!is_valid_tag(tags)){
        ret.success = false;
        ret.message = (char*)"Only NXP NTAG21x supports password";
        return ret;
    }
    if (password != NULL) {
        ret.success = tags->unlockTag(*password);
        if (!ret.success) {
            ret.message = (char*)"Unlock failed";
            return ret;
        }
    }
    auto tagData = tags->readTags();
    if (tagData.status != 0) {
        ret.success = false;
        ret.message = (char*)"Failed to read tags";
        return ret;
    }
    tags->reset();
    tagsRead = tagData.tags.toStruct();
    auto tagJson = tagsRead.toJSON();
    ret.message = tagJson;
    ret.success = true;
    ret.freeMessage = true;
    return ret;
}