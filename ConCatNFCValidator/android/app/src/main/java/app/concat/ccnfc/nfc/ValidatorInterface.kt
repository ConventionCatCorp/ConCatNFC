package app.concat.ccnfc.nfc

import android.nfc.Tag

// Interface outlining how NFC Validation should be interacted with
// This is so both Offline and Remote validation can be performed in the future
interface ValidatorInterface {
    fun validate(tag: Tag): Boolean // Take arbitrary NFC byte data and return a rich object
}