package com.example.concatnfc.utils

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.concatnfc.api.AuthService
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import okhttp3.JavaNetCookieJar
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import okhttp3.CookieJar
import okhttp3.Cookie
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.net.CookieManager
import java.net.CookiePolicy
import java.util.concurrent.TimeUnit

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

object ApiClient {
    private const val BASE_URL = "http://10.0.2.2:8080/" // Default URL, will be updated by user input
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

    fun getAuthToken(context: Context): String? {
        return runBlocking {
            context.dataStore.data.map { preferences ->
                preferences[stringPreferencesKey("auth_token")]
            }.first()
        }
    }
}
