package app.concat.nfcvalidator.utils

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import app.concat.nfcvalidator.api.AuthService
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import okhttp3.JavaNetCookieJar
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.net.CookieManager
import java.net.CookiePolicy
import java.util.concurrent.TimeUnit

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

object ApiClient {
    private const val BASE_URL = "https://" // Default URL, will be updated by user input
    private var retrofit: Retrofit? = null
    private var authService: AuthService? = null

    private val cookieManager = CookieManager().apply {
        setCookiePolicy(CookiePolicy.ACCEPT_ALL)
    }

    val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .cookieJar(JavaNetCookieJar(cookieManager))
        .addNetworkInterceptor(
            HttpLoggingInterceptor().apply {
                setLevel(HttpLoggingInterceptor.Level.BODY)
            }
        )
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

    // Save username and password
    suspend fun saveCredentials(context: Context, username: String, password: String) {
        val usernameKey = stringPreferencesKey("saved_username")
        val passwordKey = stringPreferencesKey("saved_password")
        context.dataStore.edit { preferences ->
            preferences[usernameKey] = username
            preferences[passwordKey] = password
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
        val passwordKey = stringPreferencesKey("saved_password")
        return context.dataStore.data.map { preferences ->
            preferences[passwordKey]
        }.first()
    }

    // Clear saved credentials
    suspend fun clearSavedCredentials(context: Context) {
        val usernameKey = stringPreferencesKey("saved_username")
        val passwordKey = stringPreferencesKey("saved_password")
        context.dataStore.edit { preferences ->
            preferences.remove(usernameKey)
            preferences.remove(passwordKey)
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
