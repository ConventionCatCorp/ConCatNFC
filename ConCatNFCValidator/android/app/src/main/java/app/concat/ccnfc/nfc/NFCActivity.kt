package app.concat.ccnfc.nfc

import android.content.Context
import android.content.Intent
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.viewModels
import app.concat.ccnfc.views.ValidatorViewModel

const val TAG = "NFCActivity"

class NFCActivity(private var validator: ValidatorInterface) : ComponentActivity(), NfcAdapter.ReaderCallback {
    private lateinit var adapter: NfcAdapter

    private val validatorViewModel: ValidatorViewModel by viewModels()

    override fun onTagDiscovered(tag: Tag?) {
        if (tag != null) {
            validator.validate(tag)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        Log.d(TAG, "onCreate called.")

        adapter = NfcAdapter.getDefaultAdapter(baseContext)

        // TODO("Is this check necessary due to the requirement for a device to support NFC as specified in AndroidManifest.xml?")
        if (adapter == null) {
            Log.d(TAG, "Adapter is null.")
            runOnUiThread { Toast.makeText(this, "No NFC adapter discovered.", Toast.LENGTH_LONG).show() }
            // TODO("Display significant error and offer alternatives as this app should require NFC.")
            return
        }

        if (!adapter!!.isEnabled) {
            Log.d(TAG, "Adapter is not enabled.")
            runOnUiThread { Toast.makeText(this, "NFC capabilities are disabled. Please enable them and restart the app.", Toast.LENGTH_LONG).show() }
            // TODO("Provide simple way to enable NFC or reissue/restart check. May need to be pulled into separate function.")
            return
        }

        Log.d(TAG, "Adapter is OK.")
        runOnUiThread { Toast.makeText(this, "NFC capability checks passed.", Toast.LENGTH_SHORT).show() }

    }

    override fun onResume() {
        super.onResume()
        Log.d(TAG, "onResume called.")

        if (adapter == null) {
            Log.d(TAG, "adapter is null, so returning.")
            // noop
            return;
        }

        // TODO("Make configurable.")
        val options = Bundle()
        Log.d(TAG, "Starting enableReaderMode")
        options.putInt(NfcAdapter.EXTRA_READER_PRESENCE_CHECK_DELAY, 500)
        adapter!!.enableReaderMode(this, this, NfcAdapter.FLAG_READER_NFC_A, options)
    }

    override fun onPause() {
        super.onPause()
        Log.d(TAG, "onPause called.")
        adapter!!.disableReaderMode(this)
    }

    companion object {
        fun newIntent(packageContext: Context): Intent {
            return Intent(packageContext, NFCActivity::class.java)
        }
    }
}