package app.concat.ccnfc.nfc

import android.nfc.NfcAdapter
import android.nfc.Tag
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity

class NFCActivity(private var validator: ValidatorInterface) : ComponentActivity(), NfcAdapter.ReaderCallback {
    private var adapter: NfcAdapter? = NfcAdapter.getDefaultAdapter(this)

    override fun onTagDiscovered(tag: Tag?) {
        if (tag != null) {
            validator.validate(tag)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // TODO("Is this check necessary due to the requirement for a device to support NFC as specified in AndroidManifest.xml?")
        if (adapter == null) {
            Toast.makeText(this, "No NFC adapter discovered.", Toast.LENGTH_LONG).show()
            // TODO("Display significant error and offer alternatives as this app should require NFC.")
            return;
        }

        if (!adapter!!.isEnabled) {
            Toast.makeText(this, "NFC capabilities are disabled. Please enable them and restart the app.", Toast.LENGTH_LONG).show()
            // TODO("Provide simple way to enable NFC or reissue/restart check. May need to be pulled into separate function.")
            return;
        }

    }

    override fun onResume() {
        super.onResume()

        if (adapter == null) {
            // noop
            return;
        }

        // TODO("Make configurable.")
        val options = Bundle();
        options.putInt(NfcAdapter.EXTRA_READER_PRESENCE_CHECK_DELAY, 500)
        adapter!!.enableReaderMode(this, this, NfcAdapter.FLAG_READER_NFC_A, options)
    }

    override fun onPause() {
        super.onPause()
        adapter!!.disableReaderMode(this)
    }
}