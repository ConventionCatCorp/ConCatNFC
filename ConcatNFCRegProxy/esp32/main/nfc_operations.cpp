#include "nfc_operations.h"
#include "ConCatTag.h"
#include <esp_log.h>


#define TAG "main"


esp_err_t get_uuid(PN532 *nfc, uint8_t *uuid, uint8_t *uidLength) {
    ESP_LOGD(TAG, "Waiting for an ISO14443A Card...");

    // Wait for an ISO14443A type cards (Mifare, etc.) with a timeout.
    return nfc->pn532_read_passive_target_id(PN532_BRTY_ISO14443A_106KBPS, uuid, uidLength, 1000);
}

esp_err_t set_nfc_password(PN532 *nfc, uint32_t pwd) {
    ESP_LOGD(TAG, "Writing password on card");
    return nfc->ntag2xx_set_password(pwd);
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
    return ret;
}