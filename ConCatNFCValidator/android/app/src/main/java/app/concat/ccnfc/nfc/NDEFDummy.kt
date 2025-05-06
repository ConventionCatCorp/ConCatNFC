package app.concat.ccnfc.nfc

import android.nfc.NdefMessage
import android.nfc.Tag
import android.nfc.tech.Ndef
import java.io.IOException

// Accepts NDEF boolean data to validate tickets
// INSECURE AS ALL HELL, purely for PoC/demo purposes!
class NDEFDummy : NFCInterface {
    override fun read(tag: Tag): ByteArray? {
        val ndef = Ndef.get(tag) ?: return null

        try {
            ndef.connect()
            val msg: NdefMessage = ndef.ndefMessage
            val bytes = msg.toByteArray()

            return bytes;
        } catch (e: IOException) {
            // ndef.ndefMessage: A blocked call will be canceled with IOException if close is called from another thread.
//            TODO("Handle IO exception")
            // Probably fail silently?
            return null
        }
    }
}