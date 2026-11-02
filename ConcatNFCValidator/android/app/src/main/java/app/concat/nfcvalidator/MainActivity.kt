package app.concat.nfcvalidator

import android.app.PendingIntent
import android.content.Intent
import android.media.SoundPool
import android.net.ConnectivityManager
import android.net.Network
import android.nfc.NdefMessage
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.MifareUltralight
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.annotation.RequiresApi
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.WifiOff
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
import app.concat.nfcvalidator.ui.auth.LoginViewModel
import app.concat.nfcvalidator.ui.theme.ConcatNFCTheme
import app.concat.nfcvalidator.utils.ApiClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import retrofit2.Response
import java.io.IOException
import java.security.KeyFactory
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import java.security.AlgorithmParameters
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.math.BigInteger

class MainActivity : ComponentActivity(), NfcAdapter.ReaderCallback {
    // Null on devices without NFC hardware
    private var mNfcAdapter: NfcAdapter? = null
    private lateinit var pendingIntent: PendingIntent

    private lateinit var soundPool: SoundPool;
    private var startId: Int = 0;
    private var successId: Int = 0;
    private var failId: Int = 0;


    private val validationState = mutableStateOf<Boolean?>(null)
    private val waitCardState = mutableStateOf<Boolean?>(null)
    private val isLoggedIn = mutableStateOf(false)

    private val loginViewModel: LoginViewModel by viewModels()

    // Public key for verifying badge signatures, cached so scans don't wait on the network
    // for it. Refetched if a signature fails to verify, in case the server rotated it.
    @Volatile
    private var cachedNfcKey: JWK? = null

    private var keepAliveJob: Job? = null

    // Badge passwords fetched in the last PASSWORD_CACHE_TTL_MS. A request that outlasts the
    // scan limit still finishes in the background and lands here, so "tap again" is instant.
    private data class CachedPassword(val password: UInt, val fetchedAt: Long)
    private val passwordCache = ConcurrentHashMap<String, CachedPassword>()

    // Whether the phone has a network, for the "no network" banner
    private val isOnline = mutableStateOf(true)
    @Volatile
    private var currentNetwork: Network? = null
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            currentNetwork = network
            runOnUiThread { isOnline.value = true }
        }

        override fun onLost(network: Network) {
            // Ignore losing a network we've already switched away from
            if (network != currentNetwork) return
            currentNetwork = null
            runOnUiThread { isOnline.value = false }
        }
    }

    companion object {
        // A guard won't wait longer than this for a badge to validate
        private const val SCAN_TIMEOUT_MS = 5_000L
        private const val MAX_SCAN_ATTEMPTS = 3
        private const val RETRY_DELAY_MS = 250L
        private val RETRYABLE_STATUS_CODES = setOf(502, 503, 504)

        // How often to touch the server so the session is renewed between scans
        private const val KEEP_ALIVE_INTERVAL_MS = 5 * 60_000L

        // How long a timed-out scan request keeps running in the background
        private const val BACKGROUND_REQUEST_TIMEOUT_MS = 30_000L
        private const val PASSWORD_CACHE_TTL_MS = 60_000L

        // APIError code for failures that never got an HTTP response
        const val NETWORK_ERROR = -1
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun MainContent(onLoginSuccess: () -> Unit = {}) {
        val context = LocalContext.current
        val coroutineScope = rememberCoroutineScope()
        var showLogoutDialog by rememberSaveable { mutableStateOf(false) }

        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            if (isLoggedIn.value) {
                // Main app content
                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    topBar = {
                        Column {
                            TopAppBar(
                                title = { Text("ConCat NFC Validator") },
                                navigationIcon = {
                                    Button(
                                        onClick = { showLogoutDialog = true },
                                        modifier = Modifier.padding(start = 8.dp, end = 8.dp),
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = MaterialTheme.colorScheme.error
                                        )
                                    ) {
                                        Text("Log out")
                                    }
                                }
                            )
                            if (!isOnline.value) {
                                NoNetworkBanner()
                            }
                        }
                    }
                ) { innerPadding ->
                    if (showLogoutDialog) {
                        AlertDialog(
                            onDismissRequest = { showLogoutDialog = false },
                            title = { Text("Log out?") },
                            text = { Text("Are you sure you want to log out?") },
                            confirmButton = {
                                TextButton(
                                    onClick = {
                                        showLogoutDialog = false
                                        coroutineScope.launch {
                                            stopKeepAlive()
                                            ApiClient.logout(context)
                                            cachedNfcKey = null
                                            passwordCache.clear()
                                            isLoggedIn.value = false
                                            // Reset validation states
                                            validationState.value = null
                                            waitCardState.value = null
                                            Log.d("NFC", "NFC Adapter disabled")
                                            mNfcAdapter?.disableReaderMode(this@MainActivity)
                                            mNfcAdapter?.disableForegroundDispatch(this@MainActivity)
                                        }
                                    }
                                ) {
                                    Text("Log out", color = MaterialTheme.colorScheme.error)
                                }
                            },
                            dismissButton = {
                                TextButton(onClick = { showLogoutDialog = false }) {
                                    Text("Cancel")
                                }
                            }
                        )
                    }
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
                    viewModel = loginViewModel,
                    onLoginSuccess = {
                        isLoggedIn.value = true
                        Log.d("NFC", "NFC Adapter enabled")
                        mNfcAdapter?.enableReaderMode(this, this,
                            NfcAdapter.FLAG_READER_NFC_A or
                            NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK or
                            NfcAdapter.FLAG_READER_NO_PLATFORM_SOUNDS
                            , null)
                        mNfcAdapter?.enableForegroundDispatch(this, pendingIntent, null, null)
                        startKeepAlive()
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

        if (mNfcAdapter?.isEnabled == false) {
            Toast.makeText(this, "NFC Not enabled.", Toast.LENGTH_LONG).show()
        }

        // The session cookie only lives in memory, so after process death we must log in
        // again (LoginScreen auto-logs in when credentials are saved)
        ApiClient.init(this)
        isLoggedIn.value = ApiClient.hasSession

        soundPool = SoundPool.Builder().setMaxStreams(3).build()
        startId = soundPool.load(this, R.raw.processing, 1)
        successId = soundPool.load(this, R.raw.input_ok_4, 1)
        failId = soundPool.load(this, R.raw.denybeep1, 1)

        enableEdgeToEdge()
        setContent {
            ConcatNFCTheme {
                if (mNfcAdapter == null) {
                    NfcNotSupported()
                } else {
                    MainContent()
                }
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    override fun onTagDiscovered(tag: Tag) {
        Log.d("NFC", "Tag discovered: ${tag.id.joinToString("") { "%02x".format(it)}}")
        soundPool.play(startId, 1f, 1f, 0, 0, 1f)
        if (!tag.techList.contains("android.nfc.tech.MifareUltralight")) return

        showPleaseNoRemoveCard()
        val mifare = MifareUltralight.get(tag)
        try {
            validateTag(tag, mifare)
        } catch (e: IOException) {
            // Includes TagLostException: the card left the field mid-read
            Log.w("NFC", "Lost contact with tag", e)
            showScanError("Card removed too early. Please hold it still and try again.")
        } catch (e: Exception) {
            Log.e("NFC", "Error validating tag", e)
            showScanError("Error reading badge: ${e.message}")
        } finally {
            hidePleaseNoRemoveCard()
            try {
                mifare.close()
            } catch (e: IOException) {
                // Already gone
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun validateTag(tag: Tag, mifare: MifareUltralight) {
        val nfc = NFC(mifare)
        mifare.connect()

        val password = try {
            getPasswordForTag(tag.id)
        } catch (e: APIError) {
            if (handleSessionExpired(e)) return
            when (e.responseCode) {
                NETWORK_ERROR -> showScanError(e.message ?: "Network error")
                404 -> rejectBadge("This tag is not registered with this convention.")
                else -> rejectBadge(e.message ?: "API error")
            }
            return
        }

        if (nfc.checkIfLocked() && !nfc.unlockTag(password)) {
            // Don't keep offering a password the tag refused
            passwordCache.remove(tagUuid(tag.id))
            rejectBadge("Cannot unlock tag.")
            return
        }
        if (nfc.checkIfLocked()) {
            rejectBadge("Tag is still locked.")
            return
        }
        val tags = nfc.readTags()

        // Everything from here on works without the card
        hidePleaseNoRemoveCard()

        val signatureTag = tags.getTag(TagId.SIGNATURE)
        if (signatureTag == null) {
            rejectBadge("Signature tag not found")
            return
        }
        val signatureBytes = signatureTag.getTagValueBytes().getOrElse { throw it }
        val signedJson = buildSignedJson(tags)

        val wasCached = cachedNfcKey != null
        val nfcPublicKey = try {
            getNFCPublicKey()
        } catch (e: APIError) {
            if (handleSessionExpired(e)) return
            if (e.responseCode == NETWORK_ERROR) {
                showScanError(e.message ?: "Network error")
            } else {
                rejectBadge(e.message ?: "API error")
            }
            return
        }
        var isValid = verifySignature(nfcPublicKey, signedJson, signatureBytes)

        if (!isValid && wasCached) {
            // The server may have rotated its key since we cached it
            try {
                val freshKey = getNFCPublicKey(forceRefresh = true)
                if (freshKey.toJSONString() != nfcPublicKey.toJSONString()) {
                    Log.i("NFC", "NFC public key changed; re-verifying")
                    isValid = verifySignature(freshKey, signedJson, signatureBytes)
                }
            } catch (e: APIError) {
                if (handleSessionExpired(e)) return
                Log.w("NFC", "Couldn't refresh NFC key: ${e.message}")
            }
        }

        showValidationResult(isValid)
        if (isValid) {
            soundPool.play(successId, 1f, 1f, 0, 0, 1f)
        } else {
            soundPool.play(failId, 1f, 1f, 0, 0, 1f)
        }
    }

    // Canonical JSON of the badge fields (sorted keys, no signature) that the server signed
    private fun buildSignedJson(tags: TagArray): String {
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
        return sortedJSONString
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun verifySignature(nfcPublicKey: JWK, signedJson: String, signatureBytes: ByteArray): Boolean {
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
        signatureValidator.update(signedJson.toByteArray())
        return signatureValidator.verify(signatureBytes)
    }

    private fun rejectBadge(message: String) {
        Log.i("NFC", "Badge rejected: $message")
        showValidationResult(false)
        soundPool.play(failId, 1f, 1f, 0, 0, 1f)
        showToast(message)
    }

    // A failed scan that says nothing about the badge itself, so no "NOT valid" screen
    private fun showScanError(message: String) {
        soundPool.play(failId, 1f, 1f, 0, 0, 1f)
        showToast(message)
    }

    private fun showToast(message: String) {
        runOnUiThread {
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
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

    // Shown until the card has been read, however long the server takes
    private fun showPleaseNoRemoveCard() {
        runOnUiThread {
            waitCardState.value = true
        }
    }

    private fun hidePleaseNoRemoveCard() {
        runOnUiThread {
            waitCardState.value = null
        }
    }


    // If the server rejected our session and silent re-login failed, send the user back to
    // the login screen instead of reporting the badge as invalid. A 401/403 while we still
    // hold a session (re-login worked but the retry was refused) is a permissions problem
    // and is reported like any other API error.
    private fun handleSessionExpired(e: APIError, fromScan: Boolean = true): Boolean {
        if ((e.responseCode != 401 && e.responseCode != 403) || ApiClient.hasSession) {
            return false
        }
        Log.i("NFC", "Session expired and re-login failed; returning to login screen")
        if (fromScan) {
            soundPool.play(failId, 1f, 1f, 0, 0, 1f)
        }
        runOnUiThread {
            stopKeepAlive()
            mNfcAdapter?.disableReaderMode(this)
            mNfcAdapter?.disableForegroundDispatch(this)
            validationState.value = null
            waitCardState.value = null
            cachedNfcKey = null
            passwordCache.clear()
            loginViewModel.showSessionExpired()
            isLoggedIn.value = false
            Toast.makeText(this, "Session expired. Please log in again.", Toast.LENGTH_LONG).show()
        }
        return true
    }

    class APIError(val responseCode: Int, message: String) : Exception(message)

    // Runs a badge-scan API call, waiting at most SCAN_TIMEOUT_MS (including any silent
    // re-login). The request itself runs in lifecycleScope with a longer limit, so one that
    // outlasts the scan still completes, and `call` can cache its result for the next tap.
    // Connection failures and gateway errors are retried; both scan endpoints are GETs, so
    // retrying is safe.
    private fun <T> scanRequest(call: suspend () -> Response<T>): Response<T> {
        val request = lifecycleScope.async(Dispatchers.IO) {
            withTimeout(BACKGROUND_REQUEST_TIMEOUT_MS) {
                var lastError: IOException? = null
                for (attempt in 1..MAX_SCAN_ATTEMPTS) {
                    try {
                        val response = call()
                        if (response.code() !in RETRYABLE_STATUS_CODES || attempt == MAX_SCAN_ATTEMPTS) {
                            return@withTimeout response
                        }
                        Log.w("NFC", "Server returned ${response.code()} (attempt $attempt), retrying")
                        response.errorBody()?.close()
                    } catch (e: IOException) {
                        Log.w("NFC", "Request failed (attempt $attempt)", e)
                        lastError = e
                    }
                    if (attempt < MAX_SCAN_ATTEMPTS) {
                        delay(RETRY_DELAY_MS * attempt)
                    }
                }
                throw lastError ?: IOException("Request failed")
            }
        }

        try {
            // Timing out here stops the wait, not the request
            return runBlocking { withTimeout(SCAN_TIMEOUT_MS) { request.await() } }
        } catch (e: TimeoutCancellationException) {
            val message = if (ApiClient.isReauthenticating) {
                "Reconnecting to the server. Please tap again in a moment."
            } else {
                "Server is slow to respond. Please tap again."
            }
            throw APIError(NETWORK_ERROR, message)
        } catch (e: IOException) {
            throw APIError(NETWORK_ERROR, "Can't reach the server. Check Wi-Fi and tap again.")
        } catch (e: RuntimeException) {
            throw APIError(500, "Error: ${e.message}")
        }
    }

    private fun tagUuid(uid: ByteArray): String = uid.joinToString("") { "%02x".format(it) }

    private fun cachePassword(uuid: String, password: UInt) {
        // A late response after logout shouldn't repopulate the cache
        if (!isLoggedIn.value) return
        val now = SystemClock.elapsedRealtime()
        passwordCache.values.removeIf { now - it.fetchedAt > PASSWORD_CACHE_TTL_MS }
        passwordCache[uuid] = CachedPassword(password, now)
    }

    private fun getCachedPassword(uuid: String): UInt? {
        val cached = passwordCache[uuid] ?: return null
        if (SystemClock.elapsedRealtime() - cached.fetchedAt > PASSWORD_CACHE_TTL_MS) {
            passwordCache.remove(uuid)
            return null
        }
        return cached.password
    }

    fun getPasswordForTag(uid: ByteArray): UInt {
        val uuid = tagUuid(uid)

        getCachedPassword(uuid)?.let {
            Log.d("NFC", "Using cached password for $uuid")
            return it
        }

        // Get base URL from preferences
        val baseUrl = ApiClient.getBaseUrl(this)

        // Make the API call, caching the result even if it arrives after the scan gave up
        val response = scanRequest {
            ApiClient.getAuthService(baseUrl).getNfcPassword(uuid).also { r ->
                val password = r.body()?.password
                if (r.isSuccessful && password != null) cachePassword(uuid, password.toUInt())
            }
        }

        if (response.isSuccessful == true) {
            if (response.body()?.password == null) {
                throw APIError(500, "Error: No password in response")
            }
            return response.body()?.password!!.toUInt()
        } else {
            throw APIError(
                responseCode = response.code(),
                message = "Server error ${response.code()} ${response.message()}".trim()
            )
        }
    }

    // Fetches the NFC key straight away and then every KEEP_ALIVE_INTERVAL_MS while the app
    // is in the foreground. That keeps the key cache warm and, because the endpoint needs a
    // session, renews an expired session between scans rather than during one.
    private fun startKeepAlive() {
        keepAliveJob?.cancel()
        keepAliveJob = lifecycleScope.launch(Dispatchers.IO) {
            while (isActive) {
                try {
                    getNFCPublicKey(forceRefresh = true)
                } catch (e: APIError) {
                    if (handleSessionExpired(e, fromScan = false)) return@launch
                    Log.w("NFC", "Keep-alive failed: ${e.message}")
                }
                delay(KEEP_ALIVE_INTERVAL_MS)
            }
        }
    }

    private fun stopKeepAlive() {
        keepAliveJob?.cancel()
        keepAliveJob = null
    }

    fun getNFCPublicKey(forceRefresh: Boolean = false): JWK {
        if (!forceRefresh) {
            cachedNfcKey?.let { return it }
        }

        // Get base URL from preferences
        val baseUrl = ApiClient.getBaseUrl(this)

        // Make the API call
        val response = scanRequest {
            ApiClient.getAuthService(baseUrl).getNfcKey().also { r ->
                val key = r.body()
                if (r.isSuccessful && key != null && isLoggedIn.value) cachedNfcKey = key
            }
        }

        if (response.isSuccessful) {
            return response.body() ?: throw APIError(response.code(), "NFC Key response body is null")
        } else {
            throw APIError(
                responseCode = response.code(),
                message = "Server error ${response.code()} ${response.message()}".trim()
            )
        }
    }

    override fun onStart() {
        super.onStart()
        val connectivityManager = getSystemService(ConnectivityManager::class.java)
        currentNetwork = connectivityManager.activeNetwork
        isOnline.value = currentNetwork != null
        connectivityManager.registerDefaultNetworkCallback(networkCallback)
    }

    override fun onStop() {
        super.onStop()
        getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(networkCallback)
    }

    override fun onPause() {
        super.onPause()
        stopKeepAlive()
        Log.d("NFC", "NFC Adapter disabled")
        mNfcAdapter?.disableReaderMode(this)
        mNfcAdapter?.disableForegroundDispatch(this)
    }

    override fun onResume() {
        super.onResume()
        // No need to update login state - it's already persisted
        if (isLoggedIn.value) {
            Log.d("NFC", "NFC Adapter enabled")
            mNfcAdapter?.enableReaderMode(this, this, NfcAdapter.FLAG_READER_NFC_A or
                    NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK or
                    NfcAdapter.FLAG_READER_NO_PLATFORM_SOUNDS, null)
            mNfcAdapter?.enableForegroundDispatch(this, pendingIntent, null, null)
            // Check the session right away: after a long break it has probably expired
            startKeepAlive()
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
fun NoNetworkBanner() {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.errorContainer
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Filled.WifiOff,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onErrorContainer
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = "No network connection. Badges can't be checked until it's back.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer
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

@Composable
fun NfcNotSupported() {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = Icons.Filled.Error,
                contentDescription = "NFC not supported",
                tint = Color.Red,
                modifier = Modifier.size(128.dp)
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "NFC not supported",
                style = MaterialTheme.typography.headlineMedium
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "This device does not have NFC hardware, so it can't be used to validate badges.",
                style = MaterialTheme.typography.bodyLarge
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
fun NfcNotSupportedPreview() {
    ConcatNFCTheme {
        NfcNotSupported()
    }
}

@Preview(showBackground = true)
@Composable
fun NoNetworkBannerPreview() {
    ConcatNFCTheme {
        NoNetworkBanner()
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
