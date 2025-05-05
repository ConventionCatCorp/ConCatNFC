package app.concat.ccnfc.nfc

import android.nfc.Tag

class OfflineValidator(private val nfc: NFCInterface) : ValidatorInterface {
    override fun validate(tag: Tag): Boolean {
        val data = nfc.read(tag)
        if (data != null) {
            return data[0].toInt() == 1
        }
        return false
    }
}