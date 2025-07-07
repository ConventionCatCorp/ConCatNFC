#ifndef CONCAT_NFC_PROXY_ESP32_CONCATTAG_H
#define CONCAT_NFC_PROXY_ESP32_CONCATTAG_H

#include <vector>
#include <unordered_map>
#include "esp_system.h"
#include "PN532.h"
#include "string.h"

class ByteArray {
public:
    ByteArray(uint8_t *in_data, size_t in_length) {
        data = (uint8_t *) malloc(in_length);
        memcpy(data, in_data, in_length);
        length = in_length;
    }
    explicit ByteArray(size_t in_length) {
        data = (uint8_t *) malloc(in_length);
        length = in_length;
    }

    ByteArray(const class ByteArray &other) {
        data = (uint8_t *) malloc(other.length);
        memcpy(data, other.data, other.length);
        length = other.length;
    }

    ~ByteArray() {
        free(data);
    }
    uint8_t *data;
    size_t length;
};

struct DoubleUint {
    uint32_t first;
    uint32_t second;
};

enum TagId {
    ATTENDEE_CONVENTION_ID = 0x01,
    SIGNATURE,
    ISSUANCE,
    TIMESTAMP,
    EXPIRATION,
};

class Tag {
public:
    Tag(uint8_t id, ByteArray data);

    uint64_t getTagValueULong();
    DoubleUint getTagValueDualUInt();
    ByteArray getTagValueBytes();

    uint8_t getId() {
        return id;
    }
private:
    uint8_t id;
    ByteArray data;
};

class TagArray {
public:
    void addTag(Tag tag);
    Tag* getTag(uint8_t id);
    DoubleUint *getAttendeeAndConvention();
    ByteArray *getSignature();
    uint64_t *getIssuance();
    uint64_t *getTimestamp();
    uint64_t *getExpiration();
    char *toJSON();

private:
    std::vector<Tag> tags;
};

class ConCatTag {
public:
    ConCatTag(PN532 *nfc);
    bool unlockTag(uint32_t password);
    bool checkIfLocked();
    ByteArray readPage(uint8_t pageAddress);
    uint8_t readByte();
    ByteArray readBytes(uint8_t length);
    TagArray readTags();

    void reset();

    PN532 *nfc;
private:
    std::unordered_map<uint8_t, ByteArray*> tagMemory;
    uint8_t tagStartPage = 0x10;
    uint8_t bytePosition = 0;
};


#endif //CONCAT_NFC_PROXY_ESP32_CONCATTAG_H
