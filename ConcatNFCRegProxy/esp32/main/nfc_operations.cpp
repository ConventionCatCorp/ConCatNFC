#include "nfc_operations.h"
#include "ConCatTag.h"
#include <esp_log.h>

#define TAG "main"

esp_err_t get_uuid(PN532 *nfc, uint8_t *uuid, uint8_t *uidLength) {
    ESP_LOGD(TAG, "Waiting for an ISO14443A Card...");

    // Wait for an ISO14443A type cards (Mifare, etc.) with a timeout.
    return nfc->pn532_read_passive_target_id(PN532_BRTY_ISO14443A_106KBPS, uuid, uidLength, 1000);
}

returnData read_tag_data(ConCatTag *tags, uint8_t expectedUUID[], uint8_t expectedUUIDLength, uint32_t *password) {
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
        ret.message = "UUID mismatch";
        return ret;
    }
    if (password != NULL) {
        ret.success = tags->unlockTag(*password);
        if (!ret.success) {
            ret.message = "Unlock failed";
            return ret;
        }
    }
    auto tagData = tags->readTags();
    tags->reset();
    auto tagJson = tagData.toJSON();
    ret.message = tagJson;
    ret.success = true;
    return ret;
}