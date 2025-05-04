package app.concat.ccnfc

// Interface outlining how NFC Validation should be interacted with
// This is so both Offline and Remote validation can be performed in the future
interface ValidatorInterface {
    fun validate() // Take arbitrary NFC byte data and return a rich object
}