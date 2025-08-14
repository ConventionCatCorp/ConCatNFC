#ifndef CONCAT_NFC_PROXY_ESP32_CONCATTAG_H
#define CONCAT_NFC_PROXY_ESP32_CONCATTAG_H

#include <vector>
#include <unordered_map>
#include "esp_system.h"
#include "PN532.h"
#include "string.h"

struct uint8_status {
    uint8_t data;
    uint8_t status;
};

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

    static Tag NewAttendeeId(uint32_t attendeeId, uint32_t conventionId);
    static Tag NewIssuance(uint32_t issuance);
    static Tag NewTimestamp(uint64_t timestamp);
    static Tag NewExpiration(uint64_t expiration);
    static Tag NewSignature(ByteArray signature);
    static ByteArray ValidateSignatureStructure(unsigned char *signature, int &errorType);

    uint8_t getId() {
        return id;
    }
private:
    uint8_t id;
    ByteArray data;
};

class TagArray;

class CardDefinition{
    public:
        CardDefinition():attendee_id(0),convention_id(0),issuance(0),timestamp(0),expiration(0),signature(nullptr){};
        void Free(){
            if (signature != nullptr){
                free(signature);
                signature = nullptr;
            }
        }
        char *toJSON();
        TagArray toTagArray();

        uint32_t attendee_id;
        uint32_t convention_id;
        uint32_t issuance;
        uint64_t timestamp;
        uint64_t expiration;
        char *signature;
};

class TagArray {
public:
    void addTag(Tag tag);
    Tag* getTag(uint8_t id);
    DoubleUint *getAttendeeAndConvention();
    ByteArray *getSignature();
    uint32_t *getIssuance();
    uint64_t *getTimestamp();
    uint64_t *getExpiration();
    
    CardDefinition toStruct();
    std::vector<Tag> getTags();

private:
    std::vector<Tag> tags;
};

struct TagArrayStatus {
    TagArray tags;
    uint8_t status;
};

class ConCatTag {
public:
    ConCatTag(PN532 *nfc);
    bool IsTagModelValid();
    bool unlockTag(uint32_t password);
    bool checkIfLocked();
    ByteArray readPage(uint8_t pageAddress);
    uint8_status readByte();
    ByteArray readBytes(uint8_t length);
    TagArrayStatus readTags();
    bool writeTags(TagArray &tags);
    bool writePage(uint8_t pageAddress, ByteArray data);

    bool reset();

    PN532 *nfc;
private:
    std::unordered_map<uint8_t, ByteArray*> tagMemory;
    uint8_t tagStartPage = 0x10;
    uint8_t bytePosition = 0;
};


#endif //CONCAT_NFC_PROXY_ESP32_CONCATTAG_H
