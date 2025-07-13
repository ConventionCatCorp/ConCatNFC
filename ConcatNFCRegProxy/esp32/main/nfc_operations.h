#ifndef NFC_OPERATIONS_H
#define NFC_OPERATIONS_H

#include "esp_system.h"
#include "PN532.h"
#include "ConCatTag.h"

struct returnData {
    bool success;
    esp_err_t errorCode;
    char *message;
};
returnData write_on_card(TagArray tagsNew, ConCatTag *tags, uint8_t expectedUUID[], uint8_t expectedUUIDLength, uint32_t *password);
bool is_valid_tag(ConCatTag *tags);
esp_err_t get_uuid(PN532 *nfc, uint8_t *uuid, uint8_t *uidLength);
returnData read_tag_data(CardDefinition &tagsRead, ConCatTag *tags, uint8_t expectedUUID[], uint8_t expectedUUIDLength, uint32_t *password);

#endif