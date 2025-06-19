package com.example.concatnfc

import android.app.PendingIntent
import android.content.Intent
import android.nfc.NdefMessage
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.MifareUltralight
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.annotation.RequiresApi
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.example.concatnfc.api.JWK
import com.example.concatnfc.ui.auth.LoginScreen
import com.example.concatnfc.ui.theme.ConcatNFCTheme
import com.example.concatnfc.utils.ApiClient
import kotlinx.coroutines.runBlocking
import java.security.KeyFactory
import java.security.Signature
import java.security.interfaces.ECKey
import java.security.interfaces.ECPublicKey
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import java.security.AlgorithmParameters
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import java.math.BigInteger

class MainActivity : ComponentActivity(), NfcAdapter.ReaderCallback {
    private lateinit var mNfcAdapter: NfcAdapter
    private lateinit var pendingIntent: PendingIntent
    private lateinit var isLoggedIn: MutableState<Boolean>
    private lateinit var nfc: NFC;
    private lateinit var validationState: MutableState<Boolean?>

    @Composable
    fun MainContent(onLoginSuccess: () -> Unit = {}) {
        val context = LocalContext.current
        isLoggedIn = remember { mutableStateOf(ApiClient.getAuthToken(context) != null) }
        validationState = remember { mutableStateOf<Boolean?>(null) }

        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            if (isLoggedIn.value) {
                // Main app content
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    Column(
                        modifier = Modifier
                            .padding(innerPadding)
                            .fillMaxSize(),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        when (validationState.value) {
                            true -> ValidationResult(isValid = true)
                            false -> ValidationResult(isValid = false)
                            null -> Greeting(
                                name = "Concat NFC",
                            )
                        }
                    }
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

    @RequiresApi(Build.VERSION_CODES.O)
    override fun onTagDiscovered(tag: Tag) {
        if (Looper.myLooper() == null) {
            Looper.prepare()
        }
        if (tag.techList.contains("android.nfc.tech.MifareUltralight")) {
            var mifare = MifareUltralight.get(tag);
            nfc = NFC(mifare);
            mifare.connect();
            while(!mifare.isConnected());
            var response: ByteArray

            var uid = tag.id
            var passwordString: String
            try {
                passwordString = getPasswordForTag(uid)
            } catch (e: APIError) {
                if (e.responseCode == 404) {
                    showValidationResult(false)
                    Toast.makeText(this, "This tag is not registered with this convention.", Toast.LENGTH_LONG).show()
                    return
                }
                showValidationResult(false)
                Toast.makeText(this, e.message, Toast.LENGTH_LONG).show()
                return
            }
            var password: UInt = passwordString.toUInt()

            var locked = nfc.checkIfLocked()

            if (locked) {
                if (!nfc.unlockTag(password)) {
                    showValidationResult(false)
                    Toast.makeText(this, "Cannot unlock tag.", Toast.LENGTH_LONG).show()
                    return
                }
            }
            locked = nfc.checkIfLocked()
            if (locked) {
                showValidationResult(false)
                Toast.makeText(this, "Tag is still locked.", Toast.LENGTH_LONG).show()
                return
            }
            val tags = nfc.readTags()

            val attendeeAndConvention = tags.getTag(TagId.ATTENDEE_CONVENTION_ID)
            val signatureTag = tags.getTag(TagId.SIGNATURE)
            if (signatureTag == null) {
                showValidationResult(false)
                Toast.makeText(this, "Signature tag not found", Toast.LENGTH_LONG).show()
                return
            }

            var nfcPublicKey: JWK
            try {
                nfcPublicKey = getNFCPublicKey()
            } catch (e: APIError) {
                showValidationResult(false)
                Toast.makeText(this, e.message, Toast.LENGTH_LONG).show()
                return
            }
            val tagsJson = tags.toJSON()
            val jsonKeys = tagsJson.keys()
            val map = mutableMapOf<String, Any>()

            for (key in jsonKeys) {
                map[key] = tagsJson.get(key)
            }
            val sortedMap = map.toSortedMap()
            var sortedJSONString = "{"
            for ((key, value) in sortedMap) {
                if (key == "signature") {
                    continue
                }
                sortedJSONString += "\"$key\":$value,"
            }
            sortedJSONString = sortedJSONString.dropLast(1)
            sortedJSONString += "}"

            val algorithmParameters = AlgorithmParameters.getInstance("EC")
            val curveName = when (nfcPublicKey.crv) {
                "P-256" -> "secp256r1" // P-256 is also known as secp256r1
                else -> throw IllegalArgumentException("Unsupported curve: ${nfcPublicKey.crv}")
            }
            algorithmParameters.init(ECGenParameterSpec(curveName))
            val ecSpec = algorithmParameters.getParameterSpec(ECParameterSpec::class.java)

            val keyFactory = KeyFactory.getInstance("EC")

            val x = BigInteger(1, Base64.getUrlDecoder().decode(nfcPublicKey.x))
            val y = BigInteger(1, Base64.getUrlDecoder().decode(nfcPublicKey.y))

            val ecPublicKey = keyFactory.generatePublic(ECPublicKeySpec(
                ECPoint(
                    x,
                    y
                ),
                ecSpec
            )) as ECPublicKey

            val signatureValidator = Signature.getInstance("SHA256withECDSA")
            signatureValidator.initVerify(ecPublicKey)
            signatureValidator.update(sortedJSONString.toByteArray())

            val signatureBytes = signatureTag.getTagValueBytes().getOrElse { throw it }

            val isValid = signatureValidator.verify(signatureBytes)

            showValidationResult(isValid)
        }
    }

    private fun showValidationResult(isValid: Boolean) {
        runOnUiThread {
            validationState.value = isValid
            android.os.Handler(Looper.getMainLooper()).postDelayed({
                // Only reset if the state hasn't been changed by a new scan
                if (validationState.value == isValid) {
                    validationState.value = null
                }
            }, 3000) // 3-second delay
        }
    }

    class APIError(val responseCode: Int, message: String) : Exception(message)

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
            throw APIError(responseCode = response?.code() ?: -1, message = response?.message() ?: "Unknown error")
        }
    }

    fun getNFCPublicKey(): JWK {
        // Get base URL from preferences
        val baseUrl = ApiClient.getBaseUrl(this)

        // Make the API call
        val response = runBlocking {
            try {
                ApiClient.getAuthService(baseUrl).getNfcKey()
            } catch (e: Exception) {
                null
            }
        }

        if (response?.isSuccessful == true) {
            return response.body() ?: throw APIError(response.code(), "NFC Key response body is null")
        } else {
            throw APIError(responseCode = response?.code() ?: -1, message = response?.message() ?: "Unknown error getting NFC Key")
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

@Composable
fun ValidationResult(isValid: Boolean, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (isValid) {
            Icon(
                imageVector = Icons.Filled.CheckCircle,
                contentDescription = "Valid Signature",
                tint = Color.Green,
                modifier = Modifier.size(256.dp)
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "Card is Valid",
                style = MaterialTheme.typography.headlineMedium
            )
        } else {
            Icon(
                imageVector = Icons.Filled.Error,
                contentDescription = "Invalid Signature",
                tint = Color.Red,
                modifier = Modifier.size(256.dp)
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "Card is NOT valid",
                style = MaterialTheme.typography.headlineMedium
            )
        }
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
