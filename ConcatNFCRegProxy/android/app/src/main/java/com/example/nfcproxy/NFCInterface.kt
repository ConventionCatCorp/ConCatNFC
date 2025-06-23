package com.example.nfcproxy

import android.content.Context
import android.util.SparseArray
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import android.util.Base64
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class NFCInterfaceException: Exception {
    constructor(message: String) : super(message)
}

fun UInt.toByteArray(byteOrder: ByteOrder = ByteOrder.BIG_ENDIAN): ByteArray {
    return ByteBuffer.allocate(UInt.SIZE_BYTES)
        .order(byteOrder)
        .putInt(this.toInt()) // Convert UInt to Int for ByteBuffer's putInt
        .array()
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

abstract class NFCInterface {
    constructor(ctx: Context) {
        mContext = ctx
    }
    val tagMemory: SparseArray<ByteArray> = SparseArray<ByteArray>() // Store data read from tag

    var mContext: Context
    var mReaderOpened: Boolean = false
    private var mCardPresent: Boolean = false
    var mCardSupported: Boolean = false
    var bytePosition: Int = 0
    val tagStartPage = 0x10
    var versionInfo: ByteArray? = null

    private var eventListener: (suspend (String) -> Unit)? = null

    fun setCardPresent(cardPresent: Boolean) {
        mCardPresent = cardPresent
        if (!mCardPresent) {
            tagMemory.clear()
            bytePosition = 0
        }
    }

    fun getCardPresent(): Boolean {
        return mCardPresent
    }

    fun setEventListener(listener: suspend (String) -> Unit) {
        this.eventListener = listener
        if (!mReaderOpened) {
            sendEvent("Reader error")
        }
    }

    protected fun sendEvent(event: String) {
        eventListener?.let {
            CoroutineScope(Dispatchers.IO).launch {
                val jsonEvent = "{\"Event\":\"$event\"}"
                it(jsonEvent)
            }
        }
    }

    abstract fun GetUUID(): String

    fun GetVersion(): ByteArray {
        if (versionInfo == null) {
            val response = transmitVendorCommand(bytes(0x60)) // Get version info
            if (response[0] != 0x0.toByte()) {
                throw NFCInterfaceException("Vendor command failed with error ${response[0]}")
            }
            versionInfo = response.copyOfRange(1, response.size)
        }
        return versionInfo!!
    }

    abstract fun transmitAndValidate(command: ByteArray): ByteArray

    fun transmitVendorCommand(command: ByteArray): ByteArray {
        var fullCommand = bytes(0xff, 0x00, 0x00, 0x00, 2 + command.size, 0xd4, 0x42)
        fullCommand += command
        val response = transmitAndValidate(fullCommand)
        if (response.size < 3) {
            throw NFCInterfaceException("response too short")
        }
        if (response[0] != 0xd5.toByte() || response[1] != 0x43.toByte()) {
            throw NFCInterfaceException("Unexpected response from Vendor command. got ${response.copyOfRange(0, 2).toHexString()}")
        }
        return response.copyOfRange(2, response.size)
    }

    // This function reads exactly one byte from the correct byte position
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

    // This function should read at least 1 page starting at pageAddress. Can read more, but must
    // be in multiples of 4
    abstract fun readPages(pageAddress: Int): ByteArray

    // This function reads a page (4 bytes) from the tag.
    fun readPage(pageAddress: Int): ByteArray {
        var data = tagMemory.get(pageAddress)
        if (data == null) {
            val readData = readPages(pageAddress)
            for (i in 0..readData.size-1 step 4) {
                tagMemory.put(pageAddress + i / 4, readData.slice(i..i + 3).toByteArray())
            }
            data = tagMemory.get(pageAddress)
        }
        if (data == null) {
            throw NFCInterfaceException("Failed to read page $pageAddress")
        }
        return data
    }

    fun readTags(): TagArray {
        val tags = TagArray()
        bytePosition = 0
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

    fun parseNtagVersion(ver: Byte, cardInfo: CardInfo): CardInfo {
        when (ver) {
            0x0f.toByte() -> {
                cardInfo.Memory = 144
                cardInfo.ProductName = "NTAG213"
            }

            0x11.toByte() -> {
                cardInfo.Memory = 504
                cardInfo.ProductName = "NTAG215"
            }

            0x13.toByte() -> {
                cardInfo.Memory = 888
                cardInfo.ProductName = "NTAG216"
            }

            else -> throw NFCInterfaceException("Unsupported card")
        }
        return cardInfo
    }

    fun getCardInfo(): CardInfo {
        var cardInfo = CardInfo(null, null, null)
        versionInfo = GetVersion()
        if (versionInfo == null) {
            throw NFCInterfaceException("Failed to get version info")
        }
        if (versionInfo!!.size < 8) {
            throw NFCInterfaceException("Unsupported card")
        }
        when (versionInfo!![1]) {
            0x04.toByte() -> cardInfo.Manufacturer = "NXP Semiconductors"
            else -> throw NFCInterfaceException("Unsupported card")
        }
        if (versionInfo!![2] != 0x04.toByte() || versionInfo!![3] != 0x02.toByte() || versionInfo!![4] != 0x01.toByte()) {
            throw NFCInterfaceException("Unsupported card")
        }
        when (versionInfo!![5]) {
            0x0.toByte() -> {
                cardInfo = parseNtagVersion(versionInfo!![6], cardInfo)
            }
        }
        return cardInfo
    }

    fun NTAG21xAuth(password: UInt) {
        val ci = getCardInfo()
        if (!ci.Manufacturer!!.startsWith("NXP") || !ci.ProductName!!.startsWith("NTAG21")) {
            throw NFCInterfaceException("Only NXP NTAG21x supports password")
        }
        var command = bytes(0x1b)
        command += password.toByteArray()
        val response = transmitVendorCommand(command)
        if (response.size < 1) {
            throw NFCInterfaceException("response too short")
        }
        if (response[0] != 0x0.toByte()) {
            throw NFCInterfaceException("Authentication failed")
        }
    }
}

data class DoubleUint(val first: UInt, val second: UInt)

data class CardInfo (
    var Manufacturer: String?,
    var ProductName: String?,
    var Memory: Int?
)

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
                TagId.SIGNATURE -> {
                    val signature = tag.getTagValueBytes()
                        .getOrElse { throw it }
                    json.put(
                        "signature", Base64.encodeToString(
                            signature, Base64.NO_WRAP)
                    )
                }
                TagId.ISSUANCE -> json.put("issuanceCount", tag.getTagValueULong()
                    .getOrElse { throw it }
                )
                TagId.TIMESTAMP -> json.put("timestamp", tag.getTagValueULong()
                    .getOrElse { throw it }
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
