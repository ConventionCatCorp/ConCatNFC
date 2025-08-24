#include <cstring>
#include <vector>
#include "ConCatTag.h"
#include "mbedtls/base64.h"
#include <esp_log.h>
#include "mbedtls/base64.h"

#define TAG "ConCatTag"

std::vector<uint8_t> uint32ToBytes(uint32_t value) {
    std::vector<uint8_t> bytes(4);
    bytes[0] = static_cast<uint8_t>((value >> 24) & 0xFF);
    bytes[1] = static_cast<uint8_t>((value >> 16) & 0xFF);
    bytes[2] = static_cast<uint8_t>((value >> 8) & 0xFF);
    bytes[3] = static_cast<uint8_t>(value & 0xFF);
    return bytes;
}

// Helper function to convert uint64_t to big-endian bytes
std::vector<uint8_t> uint64ToBytes(uint64_t value) {
    std::vector<uint8_t> bytes(8);
    for (int i = 0; i < 8; ++i) {
        bytes[i] = static_cast<uint8_t>((value >> (56 - i * 8)) & 0xFF);
    }
    return bytes;
}

void TagArray::addTag(Tag tag) {
    tags.push_back(tag);
}

std::vector<Tag> TagArray::getTags(){
    return tags;
}

Tag *TagArray::getTag(uint8_t id) {
    for (Tag &t : tags)
    {
        if (t.getId() == id)
        {
            return &t;
        }
    }
    return nullptr;
}

DoubleUint *TagArray::getAttendeeAndConvention()
{
    Tag *tag = getTag(ATTENDEE_CONVENTION_ID);
    if (tag == nullptr) {
        return nullptr;
    }
    return new DoubleUint(tag->getTagValueDualUInt());
}

ByteArray *TagArray::getSignature()
{
    Tag *tag = getTag(SIGNATURE);
    if (tag == nullptr) {
        return nullptr;
    }
    return new ByteArray(tag->getTagValueBytes());
}

uint32_t *TagArray::getIssuance()
{
    Tag *tag = getTag(ISSUANCE);
    if (tag == nullptr) {
        return nullptr;
    }
    return new uint32_t(tag->getTagValueULong());
}

uint64_t *TagArray::getTimestamp()
{
    Tag *tag = getTag(TIMESTAMP);
    if (tag == nullptr) {
        return nullptr;
    }
    return new uint64_t(tag->getTagValueULong());
}

uint64_t *TagArray::getExpiration()
{
    Tag *tag = getTag(EXPIRATION);
    if (tag == nullptr) {
        return nullptr;
    }
    return new uint64_t(tag->getTagValueULong());
}



CardDefinition TagArray::toStruct(){
    CardDefinition def;
    for (Tag &t : tags) {
        switch (t.getId()) {
            case ATTENDEE_CONVENTION_ID: {
                DoubleUint du = t.getTagValueDualUInt();
                def.attendee_id = du.first;
                def.convention_id = du.second;
                break;
            }
            case SIGNATURE: {
                ByteArray ba = t.getTagValueBytes();
                auto *pszBase64 = (unsigned char *)malloc(ba.length * 4 + 1);
                size_t szBase64Len;
                mbedtls_base64_encode(pszBase64, ba.length * 4 + 1, &szBase64Len, ba.data, ba.length);
                def.signature = strdup((const char*)pszBase64);
                free(pszBase64);
                break;
            }
            case ISSUANCE:
                def.issuance = t.getTagValueULong();
                break;
            case TIMESTAMP:
                def.timestamp = t.getTagValueULong();
                break;
            case EXPIRATION:
                def.expiration = t.getTagValueULong();
                break;
        }
    }
    return def;
}


char *CardDefinition::toJSON(){
    char szBuffer[1024];
    uint32_t szBufferPos = 0;

    szBufferPos = sprintf(szBuffer, "{");

    if (attendee_id != 0) {
        szBufferPos += sprintf(szBuffer + szBufferPos, R"("attendeeId":%lu,)", attendee_id);
    }

    if (convention_id != 0){
        szBufferPos += sprintf(szBuffer + szBufferPos, R"("conventionId":%lu,)", convention_id);
    }

    if (signature != nullptr && signature[0] != '\0') {
        szBufferPos += sprintf(szBuffer + szBufferPos, R"("signature":"%s",)", signature);
    }

    // Add issuance if it exists
    if (issuance != 0) {
        szBufferPos += sprintf(szBuffer + szBufferPos, R"("issuance":%lu,)", issuance);
    }

    // Add timestamp if it exists
    if (timestamp != 0) {
        szBufferPos += sprintf(szBuffer + szBufferPos, R"("timestamp":"%llu",)", timestamp);
    }

    if (expiration != 0) {
        szBufferPos += sprintf(szBuffer + szBufferPos, R"("expiration":"%llu",)", expiration);
    }

    if (szBufferPos > 1) {
        szBuffer[szBufferPos - 1] = '}';
    } else {
        szBufferPos += sprintf(szBuffer + szBufferPos, "}");
    }

    szBuffer[szBufferPos] = '\0';
    return strdup(szBuffer);
}

TagArray CardDefinition::toTagArray(){
    TagArray tags;


    tags.addTag(Tag::NewAttendeeId(attendee_id, convention_id));
    tags.addTag(Tag::NewIssuance(issuance));
    tags.addTag(Tag::NewTimestamp(timestamp));
    if (expiration != 0) {
        tags.addTag(Tag::NewExpiration(expiration));
    }

    size_t output_len;
    unsigned char* decoded = (unsigned char*)malloc(strlen(signature) * 3 / 4 + 1);
    mbedtls_base64_decode(decoded, strlen(signature) * 3 / 4 + 1, &output_len,
                             (const unsigned char*)signature, strlen(signature));
    ByteArray bytes(decoded, output_len);
    tags.addTag(Tag::NewSignature(bytes));
    free(decoded);

    return tags;
}

Tag Tag::NewAttendeeId(uint32_t attendeeId, uint32_t conventionId) {
    std::vector<uint8_t> data;
    auto attendeeBytes = uint32ToBytes(attendeeId);
    auto conventionBytes = uint32ToBytes(conventionId);
    
    data.insert(data.end(), attendeeBytes.begin(), attendeeBytes.end());
    data.insert(data.end(), conventionBytes.begin(), conventionBytes.end());
    
    return Tag(ATTENDEE_CONVENTION_ID, ByteArray(data.data(), data.size()));
}

Tag Tag::NewIssuance(uint32_t issuance) {
    auto bytes = uint32ToBytes(issuance);
    return Tag(ISSUANCE, ByteArray(bytes.data(), bytes.size()));
}

Tag Tag::NewTimestamp(uint64_t timestamp) {
    auto bytes = uint64ToBytes(timestamp);
    return Tag(TIMESTAMP, ByteArray(bytes.data(), bytes.size()));
}

Tag Tag::NewExpiration(uint64_t expiration) {
    auto bytes = uint64ToBytes(expiration);
    return Tag(EXPIRATION, ByteArray(bytes.data(), bytes.size()));
}

Tag Tag::NewSignature(ByteArray signature) {
    return Tag(SIGNATURE, signature);
}

ByteArray Tag::ValidateSignatureStructure(unsigned char *signature, int &errorType){
    if (signature == nullptr){
        errorType = 0;
        return ByteArray(nullptr, 0);
    }   
    size_t output_len = 0;
    unsigned char* output = new unsigned char[256];
    int ret = mbedtls_base64_decode(
        output, 
        256,
        &output_len,
        signature,
        strlen((const char*)signature)
    );

    if (ret != 0) {
        ESP_LOGE(TAG, "Base64 decode failed");
        errorType = 1;
        return ByteArray(nullptr, 0);
    }

    ByteArray result(output, output_len);
    delete []output;
    return result;
}

Tag::Tag(uint8_t in_id, ByteArray in_data): data(in_data.data, in_data.length) {
    id = in_id;
}


uint64_t Tag::getTagValueULong() {
    switch (data.length) {
        case 4:
            return data.data[0] << 24 | data.data[1] << 16 | data.data[2] << 8 | data.data[3];
        case 8:
            return (uint64_t(data.data[0]) << 56) |
                (uint64_t(data.data[1]) << 48) |
                (uint64_t(data.data[2]) << 40) |
                (uint64_t(data.data[3]) << 32) |
                (uint64_t(data.data[4]) << 24) |
                (uint64_t(data.data[5]) << 16) |
                (uint64_t(data.data[6]) << 8)  |
                (uint64_t(data.data[7]));
        default:
            return 0;
    }
};

DoubleUint Tag::getTagValueDualUInt() {
    switch (data.length) {
        case 8:
        {
            DoubleUint du;
            du.first = data.data[0] << 24 | data.data[1] << 16 | data.data[2] << 8 | data.data[3];
            du.second = data.data[4] << 24 | data.data[5] << 16 | data.data[6] << 8 | data.data[7];
            return du;
        }
        default:
            return DoubleUint(0, 0);
    }
}

ByteArray Tag::getTagValueBytes() {
    return data;
}

ConCatTag::ConCatTag(PN532 *i_nfc) {
    nfc = i_nfc;
}

bool ConCatTag::IsTagModelValid(){
    NTAG2XX_INFO ntag_model;

    esp_err_t err = nfc->ntag2xx_get_model(&ntag_model);
    if (err != ESP_OK)
        return false;

    switch (ntag_model.model) {
        case NTAG2XX_NTAG213:
            return true;
        case NTAG2XX_NTAG215:
            return true;
        case NTAG2XX_NTAG216:
            return true;
        default:
            return false;
    }
}

bool ConCatTag::unlockTag(uint32_t password) {
    uint8_t passwordBytes[4];
    uint32_to_big_endian_bytes(password, passwordBytes);
    esp_err_t err = nfc->ntag2xx_authenticate(passwordBytes);
    if (err != ESP_OK) {
        return false;
    }
    return true;
}

bool ConCatTag::checkIfLocked() {
    uint8_t data[4];
    esp_err_t err = nfc->ntag2xx_read_page(0x10, data, 4);
    if (err != ESP_OK) {
        return false;
    }
    return true;
}

bool ConCatTag::writePage(uint8_t pageAddress, ByteArray data) {
    if (data.length != 4){
        ESP_LOGI(TAG, "Error writing page with size %d", data.length);
        return false;
    }
    if (data.data == nullptr){
        return false;
    }
    ESP_LOGI(TAG, "Writing page %d", pageAddress);
    ESP_LOG_BUFFER_HEX_LEVEL(TAG, data.data, 4, ESP_LOG_DEBUG);
    esp_err_t err = nfc->ntag2xx_write_page(pageAddress, data.data);
    if (err != ESP_OK) {
        ESP_LOGE(TAG, "Failed to write page %d", pageAddress);
        return false;
    }
    ESP_LOGI(TAG, "Write page success %d with error %d", pageAddress, err);
    return true;
}

ByteArray ConCatTag::readPage(uint8_t pageAddress) {
    if (!tagMemory.contains(pageAddress)) {
        ESP_LOGI(TAG, "Reading page %d", pageAddress);
        uint8_t data[4];
        esp_err_t err = nfc->ntag2xx_read_page(pageAddress, data, 4);
        if (err != ESP_OK) {
            ESP_LOGE(TAG, "Failed to read page %d", pageAddress);
            return ByteArray{nullptr, 0};
        }
        ESP_LOGI(TAG, "Storing page %d", pageAddress);
        tagMemory[pageAddress] = new ByteArray(data, 4);
    }
    ESP_LOGI(TAG, "Returning page %d", pageAddress);
    return *tagMemory[pageAddress];
}

uint8_status ConCatTag::readByte() {
    ESP_LOGD(TAG, "Reading byte at pos %d", bytePosition);
    auto thisPage = readPage(tagStartPage + bytePosition / 4);
    if (thisPage.data == nullptr){
        return uint8_status{0, 1};
    }
    ESP_LOGD(TAG, "Got Page %d: ", tagStartPage + bytePosition / 4);
    ESP_LOG_BUFFER_HEX_LEVEL(TAG, thisPage.data, 4, ESP_LOG_DEBUG);
    uint8_t byte = thisPage.data[bytePosition % 4];
    bytePosition++;
    return {byte, 0}; // Return the byte and the position of the next byte;
}

ByteArray ConCatTag::readBytes(uint8_t length) {
    ByteArray bytes(length);
    for (int i = 0; i < length; i++) {
        auto result = readByte();
        if (result.status != 0) {
            return ByteArray{nullptr, 0};
        }
        bytes.data[i] = result.data;
    }
    return bytes;
}

TagArrayStatus ConCatTag::readTags() {
    auto tags = TagArray();
    bytePosition = 0;
    while (true) {
        auto result = readByte();
        if (result.status != 0) {
            return {{}, 1};
        }
        uint8_t tag = result.data;
        if (tag == 0) {
            break;
        }
        ESP_LOGD(TAG, "Tag: %d", tag);
        auto length = readByte();
        if (length.status != 0) {
            return {{}, 1};
        }
        ESP_LOGD(TAG, "Length: %d", length.data);
        auto data = readBytes(length.data);
        ESP_LOGD(TAG, "Data: ");
        ESP_LOG_BUFFER_HEX_LEVEL(TAG, data.data, data.length, ESP_LOG_DEBUG);
        tags.addTag(Tag(tag, data));
    }

    return {tags, 0};
}

bool ConCatTag::format(){
    uint8_t page = tagStartPage;
    NTAG2XX_INFO ntag_model;
    esp_err_t err = nfc->ntag2xx_get_model(&ntag_model);
    if (err != ESP_OK)
        return false;

    while (page <= ntag_model.lastUserMemoryPage) {
        uint8_t chunk[4] = {0, 0, 0, 0};
        ByteArray chunkData(chunk, 4);

        ESP_LOGD(TAG, "Sending chunk of sized 4: ");
        ESP_LOG_BUFFER_HEX_LEVEL(TAG, chunk, 4, ESP_LOG_DEBUG);

        if (!writePage(page, chunkData)) {
            return false;
        }
        page++;
    }
    return true;
}

bool ConCatTag::writeTags(TagArray &tags){
    uint8_t page = tagStartPage;
    uint8_t *bytes = new uint8_t[1024];
    uint16_t writePos = 0;
    for (Tag &t : tags.getTags()) {
        bytes[writePos++] = t.getId();
        if (writePos >= 1000){
            delete []bytes;
            return false;
        }
        auto bArr = t.getTagValueBytes();
        bytes[writePos++] = bArr.length;
        for (uint16_t i=0;i<bArr.length;i++){
            bytes[writePos++] = bArr.data[i];
            if (writePos >= 1000){
                delete []bytes;
                return false;
            }
        }
    }
    ESP_LOGD(TAG, "Data to be writen: ");
    ESP_LOG_BUFFER_HEX_LEVEL(TAG, bytes, writePos, ESP_LOG_DEBUG);
    uint16_t bytesWritten = 0;
    
    while (bytesWritten < writePos) {
        uint8_t chunk[4] = {0, 0, 0, 0}; 
        uint8_t bytesInChunk = 0;
        
        for (; bytesInChunk < 4 && bytesWritten < writePos; bytesInChunk++) {
            chunk[bytesInChunk] = bytes[bytesWritten++];
        }
        ESP_LOGD(TAG, "Sending chunk of sized 4: ");
        ESP_LOG_BUFFER_HEX_LEVEL(TAG, chunk, 4, ESP_LOG_DEBUG);
       
        ByteArray chunkData(chunk, 4);
        

        if (!writePage(page, chunkData)) {
            delete []bytes;
            return false; 
        }

        page++;
    }
    delete []bytes;
    return true; 
}


bool ConCatTag::reset() {
    tagMemory.clear();
    bytePosition = 0;
    esp_err_t err = nfc->pn532_reset_card();
    if (err != ESP_OK) {
        return false;
    }
    return true;
}