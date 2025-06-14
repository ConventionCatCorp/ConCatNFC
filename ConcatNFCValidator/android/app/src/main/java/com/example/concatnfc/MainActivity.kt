package com.example.concatnfc

import android.app.PendingIntent
import android.content.Intent
import android.nfc.NdefMessage
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.MifareUltralight
import android.os.Bundle
import android.os.Looper
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.example.concatnfc.ui.auth.LoginScreen
import com.example.concatnfc.ui.theme.ConcatNFCTheme
import com.example.concatnfc.utils.ApiClient
import kotlinx.coroutines.runBlocking
import java.nio.ByteBuffer
import java.nio.ByteOrder

class MainActivity : ComponentActivity(), NfcAdapter.ReaderCallback {
    private lateinit var mNfcAdapter: NfcAdapter
    private lateinit var pendingIntent: PendingIntent
    private lateinit var isLoggedIn: MutableState<Boolean>

    @Composable
    fun MainContent(onLoginSuccess: () -> Unit = {}) {
        val context = LocalContext.current
        isLoggedIn = remember { mutableStateOf(ApiClient.getAuthToken(context) != null) }

        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            if (isLoggedIn.value) {
                // Main app content
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    Greeting(
                        name = "Concat NFC",
                        modifier = Modifier.padding(innerPadding)
                    )
                }
            } else {
                LoginScreen(
                    onLoginSuccess = {
                        isLoggedIn.value = true
                        mNfcAdapter.enableReaderMode(this, this, NfcAdapter.FLAG_READER_NFC_A, null)
                        mNfcAdapter.enableForegroundDispatch(this, pendingIntent, null, null)
                        onLoginSuccess()
                    }
                )
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val intent = Intent(this, javaClass).apply {
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        pendingIntent = PendingIntent.getActivity(this, 0, intent,
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

        mNfcAdapter = NfcAdapter.getDefaultAdapter(this)

        if (mNfcAdapter == null) {
            Toast.makeText(this, "No NFC hardware found.", Toast.LENGTH_LONG).show()
        } else {
            if (!mNfcAdapter.isEnabled) {
                Toast.makeText(this, "NFC Not enabled.", Toast.LENGTH_LONG).show()
            }
        }

        mNfcAdapter.disableReaderMode(this)
        mNfcAdapter.disableForegroundDispatch(this)

        enableEdgeToEdge()
        setContent {
            ConcatNFCTheme {
                MainContent()
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
        if (Looper.myLooper() == null) {
            Looper.prepare()
        }
        if (tag.techList.contains("android.nfc.tech.MifareUltralight")) {
            var mifare = MifareUltralight.get(tag);
            mifare.connect();
            while(!mifare.isConnected());
            var response: ByteArray

/*            mifare.transceive(
                byteArrayOf(
                    0xA2.toByte(),  // WRITE
                    0x0a.toByte(),  //u page address
                    0xaa.toByte(),
                    0xbb.toByte(),
                    0xcc.toByte(),
                    0xdd.toByte()
                )
            )*/
            var uid = tag.id
            var passwordString: String
            try {
                passwordString = getPasswordForTag(uid)
            } catch (e: PasswordError) {
                if (e.responseCode == 404) {
                    Toast.makeText(this, "This tag is not registered with this convention.", Toast.LENGTH_LONG).show()
                    return
                }
                Toast.makeText(this, e.message, Toast.LENGTH_LONG).show()
                return
            }
            var password: UInt = passwordString.toUInt()

            var locked = checkIfLocked(mifare)

            if (locked) {
                if (!unlockTag(password, mifare)) {
                    Toast.makeText(this, "Cannot unlock tag.", Toast.LENGTH_LONG).show()
                    return
                }
            }
            locked = checkIfLocked(mifare)
            if (locked) {
                Toast.makeText(this, "Tag is locked.", Toast.LENGTH_LONG).show()
                return
            }
            Toast.makeText(this, "Tag is unlocked.", Toast.LENGTH_LONG).show()

/*            response = mifare.transceive(
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
            Toast.makeText(this, response.toString(), Toast.LENGTH_LONG).show();*/
        }
    }

    fun UInt.toByteArrayBigEndian(): ByteArray {
        return ByteBuffer.allocate(UInt.SIZE_BYTES)
            .order(ByteOrder.BIG_ENDIAN)
            .putInt(this.toInt())
            .array()
    }


    fun unlockTag(password: UInt, mifare: MifareUltralight): Boolean {
        var response: ByteArray
        var passwordBytes = password.toByteArrayBigEndian()
        try {
            response = mifare.transceive(
                byteArrayOf(
                    0x1b.toByte(),  // PWD_AUTH
                    passwordBytes[0],
                    passwordBytes[1],
                    passwordBytes[2],
                    passwordBytes[3]
                )
            )
        } catch (e: Exception) {
            return false
        }
        return true
    }

    fun checkIfLocked(mifare: MifareUltralight): Boolean {
        var response: ByteArray
        try {
            response = mifare.transceive(
                byteArrayOf(
                    0x30.toByte(),  // READ
                    0x10.toByte() // page address
                )
            )
        } catch (e: Exception) {
            return true
        }
        return false
    }

    class PasswordError(val responseCode: Int, message: String) : Exception(message)

    fun getPasswordForTag(uid: ByteArray): String {
        // Convert byte array to hex string
        val uuid = uid.joinToString("") { "%02x".format(it) }

        // Get base URL from preferences
        val baseUrl = ApiClient.getBaseUrl(this)

        // Make the API call
        val response = runBlocking {
            try {
                ApiClient.getAuthService(baseUrl).getNfcPassword(uuid)
            } catch (e: Exception) {
                null
            }
        }

        if (response?.isSuccessful == true) {
            return response.body()?.nfcPassword ?: "Error: No password in response"
        } else {
            throw PasswordError(responseCode = response?.code() ?: -1, message = response?.message() ?: "Unknown error")
        }
    }

    override fun onPause() {
        super.onPause()
        mNfcAdapter.disableReaderMode(this)
        mNfcAdapter.disableForegroundDispatch(this)
    }

    override fun onResume() {
        super.onResume()
        if (this::isLoggedIn.isInitialized && isLoggedIn.value) {
            mNfcAdapter.enableReaderMode(this, this, NfcAdapter.FLAG_READER_NFC_A, null)
            mNfcAdapter.enableForegroundDispatch(this, pendingIntent, null, null)
        }
    }
}

@Composable
fun Greeting(name: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "Welcome to $name!",
            style = MaterialTheme.typography.headlineMedium
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "You are now logged in. Please place an NFC badge on the back of your phone.",
            style = MaterialTheme.typography.bodyLarge
        )
    }
}


@Preview(showBackground = true)
@Composable
fun GreetingPreview() {
    ConcatNFCTheme {
        Greeting("Concat NFC")
    }
}

@Preview(showBackground = true)
@Composable
fun LoginPreview() {
    ConcatNFCTheme {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            LoginScreen(onLoginSuccess = {})
        }
    }
}
