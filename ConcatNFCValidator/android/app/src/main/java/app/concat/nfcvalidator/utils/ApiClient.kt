package app.concat.nfcvalidator.utils

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import android.util.Log
import app.concat.nfcvalidator.BuildConfig
import app.concat.nfcvalidator.api.AuthService
import app.concat.nfcvalidator.api.model.LoginRequest
import com.google.gson.Gson
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.JavaNetCookieJar
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.io.IOException
import java.net.CookieManager
import java.net.CookiePolicy
import java.util.concurrent.TimeUnit

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

object ApiClient {
    private const val BASE_URL = "https://" // Default URL, will be updated by user input
    private const val TAG = "ApiClient"
    private var retrofit: Retrofit? = null
    private var authService: AuthService? = null

    private val cookieManager = CookieManager().apply {
        setCookiePolicy(CookiePolicy.ACCEPT_ALL)
    }

    // Application context, used to read saved credentials when re-authenticating
    @Volatile
    private var appContext: Context? = null

    // True while the in-memory cookie jar holds a session the server accepted.
    // Cookies are not persisted, so this is false after process death.
    @Volatile
    var hasSession = false
        private set

    // Bumped on every login attempt so concurrent 401s only trigger one re-login
    private val reauthLock = Any()
    private var sessionGeneration = 0

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    private fun isAuthRequest(request: Request): Boolean {
        val path = request.url.encodedPath
        return path.endsWith("/api/login") || path.endsWith("/api/health")
    }

    private fun markLoginResult(success: Boolean) {
        synchronized(reauthLock) {
            sessionGeneration++
            hasSession = success
        }
    }

    // On 401/403 from an authenticated endpoint, log in again with the saved
    // credentials and retry the request once.
    private val sessionInterceptor = Interceptor { chain ->
        val request = chain.request()
        if (isAuthRequest(request)) {
            val response = chain.proceed(request)
            if (request.url.encodedPath.endsWith("/api/login") && response.isSuccessful) {
                markLoginResult(true)
            }
            return@Interceptor response
        }

        val generation = synchronized(reauthLock) { sessionGeneration }
        val response = chain.proceed(request)
        if (response.code != 401 && response.code != 403) {
            return@Interceptor response
        }

        if (!reauthenticate(generation)) {
            return@Interceptor response
        }
        response.close()
        chain.proceed(request)
    }

    // True while a silent re-login is in progress, so a scan that times out can tell the
    // user the app is reconnecting rather than that the network is slow
    @Volatile
    var isReauthenticating = false
        private set

    private fun reauthenticate(generation: Int): Boolean {
        synchronized(reauthLock) {
            // Another request already re-logged in (or failed to) since this one was sent
            if (sessionGeneration != generation) return hasSession

            isReauthenticating = true
            val success = try {
                loginWithSavedCredentials()
            } catch (e: IOException) {
                Log.w(TAG, "Re-login failed", e)
                false
            } finally {
                isReauthenticating = false
            }
            if (!success) markLoginResult(false)
            Log.i(TAG, "Session re-login ${if (success) "succeeded" else "failed"}")
            return success
        }
    }

    // Uses synchronous calls so re-login doesn't need a free dispatcher slot
    private fun loginWithSavedCredentials(): Boolean {
        val context = appContext ?: return false
        val (username, password) = runBlocking {
            getSavedUsername(context) to getSavedPassword(context)
        }
        if (username.isNullOrEmpty() || password.isNullOrEmpty()) return false

        val baseUrl = normalizeBaseUrl(getBaseUrl(context)) ?: return false

        val healthRequest = Request.Builder()
            .url(baseUrl.resolve("api/health") ?: return false)
            .header("Accept", "application/json")
            .build()
        httpClient.newCall(healthRequest).execute().use { response ->
            if (!response.isSuccessful) return false
        }

        val csrfToken = httpClient.cookieJar.loadForRequest(baseUrl)
            .find { it.name == "csrf" }?.value ?: return false

        val body = Gson().toJson(LoginRequest(usernameOrMail = username, password = password))
            .toRequestBody("application/json".toMediaType())
        val loginRequest = Request.Builder()
            .url(baseUrl.resolve("api/login") ?: return false)
            .header("x-csrf", csrfToken)
            .post(body)
            .build()
        httpClient.newCall(loginRequest).execute().use { response ->
            return response.isSuccessful
        }
    }

    private fun normalizeBaseUrl(url: String): HttpUrl? =
        (if (url.endsWith("/")) url else "$url/").toHttpUrlOrNull()

    // Per-stage limits, mainly for login. Badge-scan calls are also capped at 5 s in total
    // by MainActivity.scanRequest, since the card is held to the phone while they run.
    val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .cookieJar(JavaNetCookieJar(cookieManager))
        .addInterceptor(sessionInterceptor)
        .apply {
            // Bodies include login passwords and badge NFC passwords, so only log them in
            // debug builds, and keep session cookies and CSRF tokens out of logcat even then
            if (BuildConfig.DEBUG) {
                addNetworkInterceptor(
                    HttpLoggingInterceptor().apply {
                        setLevel(HttpLoggingInterceptor.Level.BODY)
                        redactHeader("Cookie")
                        redactHeader("Set-Cookie")
                        redactHeader("x-csrf")
                    }
                )
            }
        }
/*        .addInterceptor(HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BODY
        })*/
        .build()

    fun getAuthService(baseUrl: String = BASE_URL): AuthService {
        if (authService == null || retrofit?.baseUrl()?.toString() != baseUrl) {
            retrofit = Retrofit.Builder()
                .baseUrl(if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/")
                .client(httpClient)
                .addConverterFactory(GsonConverterFactory.create())

                .build()
            authService = retrofit?.create(AuthService::class.java)
        }
        return authService ?: throw IllegalStateException("AuthService not initialized")
    }

    fun clearCookies() {
        cookieManager.cookieStore.removeAll()
        markLoginResult(false)
    }

    suspend fun saveBaseUrl(context: Context, url: String) {
        context.dataStore.edit { preferences ->
            preferences[stringPreferencesKey("base_url")] = url
        }
    }

    fun getBaseUrl(context: Context): String {
        return runBlocking {
            context.dataStore.data.map { preferences ->
                preferences[stringPreferencesKey("base_url")] ?: BASE_URL
            }.first()
        }
    }

    suspend fun saveAuthToken(context: Context, token: String) {
        context.dataStore.edit { preferences ->
            preferences[stringPreferencesKey("auth_token")] = token
        }
    }

    suspend fun getAuthToken(context: Context): String? {
        val authTokenKey = stringPreferencesKey("auth_token")
        return context.dataStore.data.map { preferences ->
            preferences[authTokenKey]
        }.first()
    }

    fun getAuthTokenSync(context: Context): String? {
        return runBlocking {
            getAuthToken(context)
        }
    }

    // Older versions stored the password in plain text under this key
    private val legacyPasswordKey = stringPreferencesKey("saved_password")
    private val encryptedPasswordKey = stringPreferencesKey("saved_password_enc")

    // Save username and password (the password is encrypted with a Keystore key)
    suspend fun saveCredentials(context: Context, username: String, password: String) {
        val usernameKey = stringPreferencesKey("saved_username")
        val encryptedPassword = CredentialCipher.encrypt(password)
        context.dataStore.edit { preferences ->
            preferences[usernameKey] = username
            preferences[encryptedPasswordKey] = encryptedPassword
            preferences.remove(legacyPasswordKey)
        }
    }

    // Get saved username
    suspend fun getSavedUsername(context: Context): String? {
        val usernameKey = stringPreferencesKey("saved_username")
        return context.dataStore.data.map { preferences ->
            preferences[usernameKey]
        }.first()
    }

    // Get saved password
    suspend fun getSavedPassword(context: Context): String? {
        val preferences = context.dataStore.data.first()

        preferences[encryptedPasswordKey]?.let { encrypted ->
            val password = CredentialCipher.decrypt(encrypted)
            if (password == null) {
                // Undecryptable (Keystore key lost), so the user has to log in again
                context.dataStore.edit { it.remove(encryptedPasswordKey) }
            }
            return password
        }

        // Migrate a plain-text password saved by an older version
        val legacyPassword = preferences[legacyPasswordKey] ?: return null
        val encryptedPassword = CredentialCipher.encrypt(legacyPassword)
        context.dataStore.edit {
            it[encryptedPasswordKey] = encryptedPassword
            it.remove(legacyPasswordKey)
        }
        return legacyPassword
    }

    // Clear saved credentials
    suspend fun clearSavedCredentials(context: Context) {
        val usernameKey = stringPreferencesKey("saved_username")
        context.dataStore.edit { preferences ->
            preferences.remove(usernameKey)
            preferences.remove(encryptedPasswordKey)
            preferences.remove(legacyPasswordKey)
        }
    }

    // Complete logout - clears all stored data and cookies
    suspend fun logout(context: Context) {
        // Clear saved credentials
        clearSavedCredentials(context)
        
        // Clear auth token
        val authTokenKey = stringPreferencesKey("auth_token")
        context.dataStore.edit { preferences ->
            preferences.remove(authTokenKey)
        }
        
        // Set logout flag to prevent auto-login
        val logoutFlagKey = stringPreferencesKey("just_logged_out")
        context.dataStore.edit { preferences ->
            preferences[logoutFlagKey] = "true"
        }
        
        // Clear cookies
        clearCookies()
    }

    // Check if user just logged out
    suspend fun hasJustLoggedOut(context: Context): Boolean {
        val logoutFlagKey = stringPreferencesKey("just_logged_out")
        return context.dataStore.data.map { preferences ->
            preferences[logoutFlagKey] == "true"
        }.first()
    }

    // Clear logout flag (called after login screen is shown)
    suspend fun clearLogoutFlag(context: Context) {
        val logoutFlagKey = stringPreferencesKey("just_logged_out")
        context.dataStore.edit { preferences ->
            preferences.remove(logoutFlagKey)
        }
    }
}
