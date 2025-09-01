package app.concat.nfcvalidator

import android.app.PendingIntent
import android.content.Intent
import android.media.SoundPool
import android.nfc.NdefMessage
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.MifareUltralight
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.annotation.RequiresApi
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import app.concat.nfcvalidator.api.JWK
import app.concat.nfcvalidator.ui.auth.LoginScreen
import app.concat.nfcvalidator.ui.theme.ConcatNFCTheme
import app.concat.nfcvalidator.utils.ApiClient
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.security.KeyFactory
import java.security.Signature
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
    private lateinit var nfc: NFC;

    private lateinit var soundPool: SoundPool;
    private var startId: Int = 0;
    private var successId: Int = 0;
    private var failId: Int = 0;


    private val validationState = mutableStateOf<Boolean?>(null)
    private val waitCardState = mutableStateOf<Boolean?>(null)
    private val isLoggedIn = mutableStateOf(false)

    companion object {
        private const val KEY_IS_LOGGED_IN = "is_logged_in"
    }

    private fun checkAuthStatus(): Boolean {
        return ApiClient.getAuthTokenSync(this) != null
    }

    private fun updateLoginState() {
        isLoggedIn.value = checkAuthStatus()
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun MainContent(onLoginSuccess: () -> Unit = {}) {
        val context = LocalContext.current
        val coroutineScope = rememberCoroutineScope()
        
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            if (isLoggedIn.value) {
                // Main app content
                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    topBar = {
                        TopAppBar(
                            title = { Text("ConCat NFC Validator") },
                            actions = {
                                Button(
                                    onClick = {
                                        coroutineScope.launch {
                                            ApiClient.logout(context)
                                            isLoggedIn.value = false
                                            // Reset validation states
                                            validationState.value = null
                                            waitCardState.value = null
                                            Log.d("NFC", "NFC Adapter disabled")
                                            mNfcAdapter.disableReaderMode(this@MainActivity)
                                            mNfcAdapter.disableForegroundDispatch(this@MainActivity)
                                        }
                                    },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = MaterialTheme.colorScheme.error
                                    )
                                ) {
                                    Text("Log out")
                                }
                            }
                        )
                    }
                ) { innerPadding ->
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
                            null -> {
                                if (waitCardState.value == true){
                                    PleaseWaitCardRead()
                                }else{
                                    Greeting(
                                        name = "ConCat NFC Validator",
                                    )
                                }

                            }
                        }
                    }
                }
            } else {
                LoginScreen(
                    onLoginSuccess = {
                        isLoggedIn.value = true
                        Log.d("NFC", "NFC Adapter enabled")
                        mNfcAdapter.enableReaderMode(this, this,
                            NfcAdapter.FLAG_READER_NFC_A or
                            NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK or
                            NfcAdapter.FLAG_READER_NO_PLATFORM_SOUNDS
                            , null)
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

        if (!mNfcAdapter.isEnabled) {
            Toast.makeText(this, "NFC Not enabled.", Toast.LENGTH_LONG).show()
        }

        // Restore login state from savedInstanceState, or check auth status if first time
        if (savedInstanceState != null) {
            isLoggedIn.value = savedInstanceState.getBoolean(KEY_IS_LOGGED_IN, false)
        } else {
            updateLoginState()
        }

        soundPool = SoundPool.Builder().setMaxStreams(3).build()
        startId = soundPool.load(this, R.raw.processing, 1)
        successId = soundPool.load(this, R.raw.input_ok_4, 1)
        failId = soundPool.load(this, R.raw.denybeep1, 1)

        enableEdgeToEdge()
        setContent {
            ConcatNFCTheme {
                MainContent()
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(KEY_IS_LOGGED_IN, isLoggedIn.value)
    }

    @RequiresApi(Build.VERSION_CODES.O)
    override fun onTagDiscovered(tag: Tag) {
        if (Looper.myLooper() == null) {
            Looper.prepare()
        }
        Log.d("NFC", "Tag discovered: ${tag.id.joinToString("") { "%02x".format(it)}}")
        soundPool.play(startId, 1f, 1f, 0, 0, 1f)
        if (tag.techList.contains("android.nfc.tech.MifareUltralight")) {

            showPleaseNoRemoveCard()

            var mifare = MifareUltralight.get(tag);
            nfc = NFC(mifare);
            mifare.connect();
            while(!mifare.isConnected());
            var response: ByteArray

            var uid = tag.id
            var password: UInt
            try {
                password = getPasswordForTag(uid)
            } catch (e: APIError) {
                if (e.responseCode == 404) {
                    showValidationResult(false)
                    soundPool.play(failId, 1f, 1f, 0, 0, 1f)
                    Log.i("NFC", "Tag not registered with this convention.")
                    Toast.makeText(this, "This tag is not registered with this convention.", Toast.LENGTH_LONG).show()
                    return
                }
                showValidationResult(false)
                soundPool.play(failId, 1f, 1f, 0, 0, 1f)
                Log.d("NFC", "API Error: ${e.message}")
                Toast.makeText(this, e.message, Toast.LENGTH_LONG).show()
                return
            }

            var locked = nfc.checkIfLocked()

            if (locked) {
                if (!nfc.unlockTag(password)) {
                    showValidationResult(false)
                    soundPool.play(failId, 1f, 1f, 0, 0, 1f)
                    Log.d("NFC", "Cannot unlock tag.")
                    Toast.makeText(this, "Cannot unlock tag.", Toast.LENGTH_LONG).show()
                    return
                }
            }
            locked = nfc.checkIfLocked()
            if (locked) {
                showValidationResult(false)
                soundPool.play(failId, 1f, 1f, 0, 0, 1f)
                Log.d("NFC", "Tag is still locked.")
                Toast.makeText(this, "Tag is still locked.", Toast.LENGTH_LONG).show()
                return
            }
            val tags = nfc.readTags()

            val attendeeAndConvention = tags.getTag(TagId.ATTENDEE_CONVENTION_ID)
            val signatureTag = tags.getTag(TagId.SIGNATURE)
            if (signatureTag == null) {
                showValidationResult(false)
                soundPool.play(failId, 1f, 1f, 0, 0, 1f)
                Log.d("NFC", "Signature tag not found.")
                Toast.makeText(this, "Signature tag not found", Toast.LENGTH_LONG).show()
                return
            }

            var nfcPublicKey: JWK
            try {
                nfcPublicKey = getNFCPublicKey()
            } catch (e: APIError) {
                showValidationResult(false)
                soundPool.play(failId, 1f, 1f, 0, 0, 1f)
                Log.d("NFC", "API Error: ${e.message}")
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
                when (key) {
                    "signature" -> continue
                    "timestamp" -> sortedJSONString += "\"$key\":\"$value\","
                    else -> sortedJSONString += "\"$key\":$value,"
                }
            }
            sortedJSONString = sortedJSONString.dropLast(1)
            sortedJSONString += "}"

            Log.d("NFC", "Sorted JSON String: $sortedJSONString")

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
            if (isValid) {
                soundPool.play(successId, 1f, 1f, 0, 0, 1f)
            } else {
                soundPool.play(failId, 1f, 1f, 0, 0, 1f)
            }
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

    private fun showPleaseNoRemoveCard() {
        runOnUiThread {
            waitCardState.value = true
            android.os.Handler(Looper.getMainLooper()).postDelayed({
                // Only reset if the state hasn't been changed by a new scan
                if (waitCardState.value == true) {
                    waitCardState.value = null
                }
            }, 3000) // 3-second delay
        }
    }


    class APIError(val responseCode: Int, message: String) : Exception(message)

    fun getPasswordForTag(uid: ByteArray): UInt {
        // Convert byte array to hex string
        val uuid = uid.joinToString("") { "%02x".format(it) }

        // Get base URL from preferences
        val baseUrl = ApiClient.getBaseUrl(this)

        // Make the API call
        val response = runBlocking {
            try {
                ApiClient.getAuthService(baseUrl).getNfcPassword(uuid)
            } catch (e: Exception) {
                throw APIError(500, "Error: ${e.message}")
            }
        }

        if (response.isSuccessful == true) {
            if (response.body()?.password == null) {
                throw APIError(500, "Error: No password in response")
            }
            return response.body()?.password!!.toUInt()
        } else {
            throw APIError(responseCode = response.code(), message = response.message())
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
        Log.d("NFC", "NFC Adapter disabled")
        mNfcAdapter.disableReaderMode(this)
        mNfcAdapter.disableForegroundDispatch(this)
    }

    override fun onResume() {
        super.onResume()
        // No need to update login state - it's already persisted
        if (isLoggedIn.value) {
            Log.d("NFC", "NFC Adapter enabled")
            mNfcAdapter.enableReaderMode(this, this, NfcAdapter.FLAG_READER_NFC_A or
                    NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK or
                    NfcAdapter.FLAG_READER_NO_PLATFORM_SOUNDS, null)
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

@Composable
fun PleaseWaitCardRead() {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {

            Icon(
                imageVector = Icons.Filled.Warning,
                contentDescription = "Please don't remove the card",
                tint = Color(0xFFFFA500),
                modifier = Modifier.size(256.dp)
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "Please don't remove the card",
                style = MaterialTheme.typography.headlineMedium
            )
        }

}

@Preview(showBackground = true)
@Composable
fun GreetingPreview() {
    ConcatNFCTheme {
        Greeting("ConCat NFC Validator")
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
