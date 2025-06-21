package com.example.concatnfc.ui.auth

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.concatnfc.utils.ApiClient
import okhttp3.Cookie
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import com.example.concatnfc.api.model.ErrorResponse
import com.example.concatnfc.api.model.LoginRequest
import com.example.concatnfc.api.model.SecondFactor
import com.google.gson.Gson
import kotlinx.coroutines.launch
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import retrofit2.HttpException
import java.io.IOException

class LoginViewModel : ViewModel() {
    var uiState by mutableStateOf(LoginUiState())
        private set

    var csrfToken: String? = null

    fun updateUrl(url: String) {
        uiState = uiState.copy(url = url)
    }

    fun updateUsername(username: String) {
        uiState = uiState.copy(username = username)
    }

    fun updatePassword(password: String) {
        uiState = uiState.copy(password = password)
    }

    fun login(onSuccess: () -> Unit, secondFactorCode: String? = null) {
        if (uiState.isLoading) return

        uiState = uiState.copy(
            isLoading = true,
            error = null
        )

        viewModelScope.launch {
            try {
                val httpUrl = uiState.url.toHttpUrlOrNull()
                    ?: throw IllegalStateException("Invalid URL: ${uiState.url}")
                // First, get CSRF token from the cookie

                val csrfResponse = ApiClient.getAuthService(uiState.url).getCsrfToken()

                if (!csrfResponse.isSuccessful) {
                    uiState = uiState.copy(
                        error = "Failed to get CSRF token: ${csrfResponse.code()} - ${csrfResponse.message()}",
                        isLoading = false
                    )
                    return@launch
                }

                // Get the CSRF token from cookies
                val cookies = ApiClient.httpClient.cookieJar.loadForRequest(httpUrl)
                val csrfCookie = cookies.find { it.name == "csrf" }

                if (csrfCookie == null) {
                    uiState = uiState.copy(
                        error = "CSRF token cookie not found in response",
                        isLoading = false
                    )
                    return@launch
                }

                csrfToken = csrfCookie.value

                val loginRequest = LoginRequest(
                    usernameOrMail = uiState.username,
                    password = uiState.password)

                // Add second factor if provided
                secondFactorCode?.let { code ->
                    loginRequest.secondFactor = SecondFactor(code)
                }
                
                // Perform login with CSRF token in headers
                val response = ApiClient.getAuthService(uiState.url).login(
                    csrfToken = csrfToken,
                    loginRequest = loginRequest
                )

                if (response.isSuccessful) {
                    // Cookies are automatically handled by the cookie manager in ApiClient
                    onSuccess()
                } else if (response.code() == 401) {
                    try {
                        val errorBody = response.errorBody()?.string()
                        val errorResponse = Gson().fromJson(errorBody, ErrorResponse::class.java)
                        
                        if (errorResponse.errors.authentication.message == "Missing second factor.") {
                            // Show 2FA dialog
                            uiState = uiState.copy(
                                isLoading = false,
                                showSecondFactorDialog = true
                            )
                        } else {
                            throw IllegalStateException(errorResponse.errors.authentication.message)
                        }
                    } catch (e: Exception) {
                        uiState = uiState.copy(
                            error = "Authentication failed: ${e.message}",
                            isLoading = false
                        )
                    }
                } else {
                    uiState = uiState.copy(
                        error = "Login failed: ${response.code()} - ${response.message()}",
                        isLoading = false
                    )
                }
            } catch (e: Exception) {
                uiState = uiState.copy(
                    error = "Error: ${e.message}",
                    isLoading = false
                )
            }
        }
    }
    
    fun submitSecondFactor(onSuccess: () -> Unit) {
        login(onSuccess, uiState.secondFactorCode)
        uiState = uiState.copy(showSecondFactorDialog = false, secondFactorCode = "")
    }

    fun dismissError() {
        uiState = uiState.copy(error = null)
    }
    
    fun updateSecondFactorCode(code: String) {
        uiState = uiState.copy(secondFactorCode = code)
    }
    
    fun showSecondFactorDialog(show: Boolean) {
        uiState = uiState.copy(showSecondFactorDialog = show)
    }
}

data class LoginUiState(
    val url: String = "",
    val username: String = "",
    val password: String = "",
    val isLoading: Boolean = false,
    val error: String? = null,
    val showSecondFactorDialog: Boolean = false,
    val secondFactorCode: String = ""
)
