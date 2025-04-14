package com.example.concatnfc

import android.app.PendingIntent
import android.content.Intent
import android.nfc.NdefMessage
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.MifareUltralight
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.example.concatnfc.ui.theme.ConcatNFCTheme

class MainActivity : ComponentActivity(), NfcAdapter.ReaderCallback {
    private lateinit var mNfcAdapter: NfcAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val intent = Intent(this, javaClass).apply {
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        var pendingIntent: PendingIntent = PendingIntent.getActivity(this, 0, intent,
            PendingIntent.FLAG_MUTABLE)

        mNfcAdapter = NfcAdapter.getDefaultAdapter(this)

        if (mNfcAdapter == null) {
            Toast.makeText(this, "No NFC hardware found.", Toast.LENGTH_LONG).show()
        } else {
            if (!mNfcAdapter.isEnabled) {
                Toast.makeText(this, "NFC Not enabled.", Toast.LENGTH_LONG).show()
            }
        }


        enableEdgeToEdge()
        setContent {
            ConcatNFCTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    Greeting(
                        name = "Android",
                        modifier = Modifier.padding(innerPadding)
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (NfcAdapter.ACTION_NDEF_DISCOVERED == intent.action) {
            intent.getParcelableArrayExtra(NfcAdapter.EXTRA_NDEF_MESSAGES)?.also { rawMessages ->
                val messages: List<NdefMessage> = rawMessages.map { it as NdefMessage }
                // Process the messages array.
            }
        }
    }

    override fun onTagDiscovered(tag: Tag) {

        if (tag.techList.contains("android.nfc.tech.MifareUltralight")) {
            var mifare = MifareUltralight.get(tag);
            mifare.connect();
            while(!mifare.isConnected());
            var response: ByteArray

/*            mifare.transceive(
                byteArrayOf(
                    0xA2.toByte(),  // WRITE
                    0x0a.toByte(),  // page address
                    0xaa.toByte(),
                    0xbb.toByte(),
                    0xcc.toByte(),
                    0xdd.toByte()
                )
            )*/

            response = mifare.transceive(
                byteArrayOf(
                    0x30.toByte(),  // READ
                    0x0a.toByte() // page address
                )
            )
            //Toast.makeText(this, response.toString(), Toast.LENGTH_LONG).show();
            response = mifare.transceive(
                byteArrayOf(
                    0x60.toByte(),  // READ
                )
            )
            Toast.makeText(this, response.toString(), Toast.LENGTH_LONG).show();
        }
    }

    override fun onResume() {
        super.onResume()
        if (mNfcAdapter != null) {
            val options = Bundle()
            options.putInt(NfcAdapter.EXTRA_READER_PRESENCE_CHECK_DELAY, 250)
            mNfcAdapter.enableReaderMode(
                this,
                this,
                NfcAdapter.FLAG_READER_NFC_A or
                        NfcAdapter.FLAG_READER_NFC_B or
                        NfcAdapter.FLAG_READER_NFC_F or
                        NfcAdapter.FLAG_READER_NFC_V,
                options
            )
        }
    }

    override fun onPause() {
        super.onPause()
        mNfcAdapter.disableReaderMode(this);
    }
}

@Composable
fun Greeting(name: String, modifier: Modifier = Modifier) {
    Text(
        text = "Hello $name!",
        modifier = modifier
    )
}

@Preview(showBackground = true)
@Composable
fun GreetingPreview() {
    ConcatNFCTheme {
        Greeting("Android")
    }
}

