package app.concat.ccnfc

import android.nfc.Tag

// Interface to allow multiple NFC tags to be implemented
interface NFCInterface {
    // Take byte data and return a boolean of whether or not a ticket is Authed
    // Will require Validator strategies
    fun isValid()

    // Write data to tag
    fun write(tag: Tag, data: ByteArray)

    // Read data from tag
    fun read(tag: Tag)
}