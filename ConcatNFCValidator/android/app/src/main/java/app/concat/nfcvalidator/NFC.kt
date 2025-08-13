package app.concat.nfcvalidator

import android.nfc.tech.MifareUltralight
import android.util.SparseArray
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.json.JSONObject



data class DoubleUint(val first: UInt, val second: UInt)

enum class TagId(val id: Byte) {
    ATTENDEE_CONVENTION_ID(0x01.toByte()),
    SIGNATURE(0x02.toByte()),
    ISSUANCE(0x03.toByte()),
    TIMESTAMP(0x04.toByte()),
    EXPIRATION(0x05.toByte());

    companion object {
        fun fromId(id: Byte): TagId? {
            return entries.find { it.id == id }
        }
    }
}

fun ByteArray.toULongLe(): ULong {
    require(size >= 8) { "ByteArray must contain at least 8 bytes for ULong conversion." }
    var result: ULong = 0uL
    for (i in 0 until 8) {
        result = result or ((this[i].toULong() and 0xFFu) shl (i * 8))
    }
    return result
}

fun ByteArray.toULongBe(): ULong {
    require(size >= 8) { "ByteArray must contain at least 8 bytes for ULong conversion." }
    var result: ULong = 0uL
    for (i in 0 until 8) {
        result = result or ((this[i].toULong() and 0xFFu) shl ((7 - i) * 8))
    }
    return result
}

fun ByteArray.toUintLe(): UInt {
    require(size >= 4) { "ByteArray must contain at least 4 bytes for ULong conversion." }
    var result: UInt = 0u
    for (i in 0 until 4) {
        result = result or ((this[i].toUInt() and 0xFFu) shl (i * 8))
    }
    return result
}

fun ByteArray.toUIntBe(): UInt {
    require(size >= 4) { "ByteArray must contain at least 4 bytes for ULong conversion." }
    var result: UInt = 0u
    for (i in 0 until 4) {
        result = result or ((this[i].toUInt() and 0xFFu) shl ((3 - i) * 8))
    }
    return result
}

class Tag(val id: Byte, val data: ByteArray) {
    fun getTagValueULong(): Result<ULong> {
        when (data.size) {
            4 -> {
                return runCatching {
                    data.toUIntBe().toULong()
                }
            }
            8 -> {
                return runCatching {
                    data.toULongBe()
                }
            }
            else -> {
                return runCatching {
                    throw Exception("Tag data is not 4 or 8 bytes long")
                }
            }
        }
    }
    fun getTagValueDualUInt(): Result<DoubleUint> {
        when (data.size) {
            8 -> {
                val byte0 = data[0].toUInt() and 0xFFu
                val byte1 = data[1].toUInt() and 0xFFu
                val byte2 = data[2].toUInt() and 0xFFu
                val byte3 = data[3].toUInt() and 0xFFu

                val uint1 = ((byte0 shl 24) or (byte1 shl 16) or (byte2 shl 8) or byte3).toUInt();

                val byte4 = data[4].toUInt() and 0xFFu
                val byte5 = data[5].toUInt() and 0xFFu
                val byte6 = data[6].toUInt() and 0xFFu
                val byte7 = data[7].toUInt() and 0xFFu

                val uint2 = ((byte4 shl 24) or (byte5 shl 16) or (byte6 shl 8) or byte7).toUInt();

                return runCatching { DoubleUint(uint1, uint2) }
            }
            else -> {
                return runCatching {
                    throw Exception("Tag data is not 8 bytes long")
                }
            }
        }
    }
    fun getTagValueBytes(): Result<ByteArray> {
        return runCatching { data }
    }
}

class TagArray {
    val tags: ArrayList<Tag> = ArrayList<Tag>()

    fun addTag(tag: Tag) {
        tags.add(tag)
    }
    fun getTag(tagId: TagId): Tag? {
        for (tag in tags) {
            if (tag.id == tagId.id) {
                return tag
            }
        }
        return null
    }

    fun getAttendeeAndConvention(): Result<DoubleUint> {
        val tag = getTag(TagId.ATTENDEE_CONVENTION_ID)
        if (tag == null) {
            return runCatching {
                throw Exception("Tag not found")
            }
        }
        return tag.getTagValueDualUInt()
    }

    fun getSignature(): Result<ByteArray> {
        val tag = getTag(TagId.SIGNATURE)
        if (tag == null) {
            return runCatching {
                throw Exception("Tag not found")
            }
        }
        return tag.getTagValueBytes()
    }

    fun getIssuance(): Result<ULong> {
        val tag = getTag(TagId.ISSUANCE)
        if (tag == null) {
            return runCatching {
                throw Exception("Tag not found")
            }
        }
        return tag.getTagValueULong()
    }

    fun getTimestamp(): Result<ULong> {
        val tag = getTag(TagId.TIMESTAMP)
        if (tag == null) {
            return runCatching {
                throw Exception("Tag not found")
            }
        }
        return tag.getTagValueULong()
    }

    fun getExpiration(): Result<ULong> {
        val tag = getTag(TagId.EXPIRATION)
        if (tag == null) {
            return runCatching {
                throw Exception("Tag not found")
            }
        }
        return tag.getTagValueULong()
    }

    fun toJSON(): JSONObject {
        val json = JSONObject()
        for (tag in tags) {
            when (TagId.fromId(tag.id)) {
                TagId.ATTENDEE_CONVENTION_ID -> {
                    val attendeeAndConvention = tag.getTagValueDualUInt()
                    json.put("userId", attendeeAndConvention
                        .getOrElse { throw it }
                        .first
                    )
                    json.put("conventionId", attendeeAndConvention
                        .getOrElse { throw it }
                        .second
                    )
                }
                TagId.SIGNATURE -> json.put("signature", tag.getTagValueBytes()
                    .getOrElse { throw it }
                )
                TagId.ISSUANCE -> json.put("issuanceCount", tag.getTagValueULong()
                    .getOrElse { throw it }
                )
                TagId.TIMESTAMP -> json.put("timestamp", tag.getTagValueULong()
                    .getOrElse { throw it }
                    .toString()
                )
                TagId.EXPIRATION -> json.put("expiration", tag.getTagValueULong()
                    .getOrElse { throw it }
                )
                else -> {}
            }
        }
        return json
    }
}

class NFC constructor(val mifare: MifareUltralight) {

    val tagMemory: SparseArray<ByteArray> = SparseArray<ByteArray>() // Store data read from tag
    val tagStartPage = 0x10
    var bytePosition = 0

    fun unlockTag(password: UInt): Boolean {
        val passwordBytes = password.toByteArrayBigEndian()
        return try {
            mifare.transceive(
                byteArrayOf(
                    0x1b.toByte(),  // PWD_AUTH
                    passwordBytes[0],
                    passwordBytes[1],
                    passwordBytes[2],
                    passwordBytes[3]
                )
            )
            true
        } catch (e: Exception) {
            false
        }
    }
    fun checkIfLocked(): Boolean {
        var response: ByteArray
        try {
            response = mifare.transceive(
                byteArrayOf(
                    0x30.toByte(),  // READ
                    0x10.toByte() // page address
                )
            )
        } catch (e: Exception) {
            return true
        }
        return false
    }
    fun UInt.toByteArrayBigEndian(): ByteArray {
        return ByteBuffer.allocate(UInt.SIZE_BYTES)
            .order(ByteOrder.BIG_ENDIAN)
            .putInt(this.toInt())
            .array()
    }

    fun readPage(pageAddress: Int): ByteArray {
        var data = tagMemory.get(pageAddress)
        if (data == null) {
            val readData = mifare.readPages(pageAddress)
            for (i in 0..readData.size-1 step 4) {
                tagMemory.put(pageAddress + i / 4, readData.slice(i..i + 3).toByteArray())
            }
            data = tagMemory.get(pageAddress)
        }
        return data
    }

    fun readByte(): Byte {
        val thisPage = readPage(tagStartPage + bytePosition / 4)
        val thisByte = thisPage[bytePosition % 4]
        bytePosition++
        return thisByte
    }

    fun readBytes(length: Int): ByteArray {
        val data = ByteArray(length)
        for (i in 0..length-1) {
            data[i] = readByte()
        }
        return data
    }

    fun readTags(): TagArray {
        val tags = TagArray()
        while (true) {
            val tag = readByte()
            if (tag == 0x0.toByte()) {
                break
            }
            val length = readByte()
            val data = readBytes(length.toInt())
            tags.addTag(Tag(tag, data))
        }
        return tags
    }
}

