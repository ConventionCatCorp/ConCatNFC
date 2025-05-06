package app.concat.ccnfc.nfc

import android.nfc.Tag

// Interface to allow multiple NFC tags to be implemented
interface NFCInterface {
    // Read data from tag
    fun read(tag: Tag): ByteArray?
}

// who doesn't love future-proofing
interface WriteableNFCInterface : NFCInterface {
    // Write data to tag
    fun write(tag: Tag, data: ByteArray)
}