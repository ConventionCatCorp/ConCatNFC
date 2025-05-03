package app.concat.ccnfc

class Comms(private var options: Options = Options()) {
    class Options(
        /* Configurable API base URI, to swap between instances */
        var api: String = DEFAULT_API
    )

    companion object {
        private const val TAG = "CommsHandler"
        val DEFAULT_API = "https://demo.concat.app/"
    }
}