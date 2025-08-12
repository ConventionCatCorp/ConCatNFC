package app.concat.nfcvalidator.ui.auth

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.concat.nfcvalidator.utils.ApiClient
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import app.concat.nfcvalidator.api.model.ErrorResponse
import app.concat.nfcvalidator.api.model.LoginRequest
import app.concat.nfcvalidator.api.model.SecondFactor
import com.google.gson.Gson
import kotlinx.coroutines.launch

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

    fun updateSavePassword(savePassword: Boolean) {
        uiState = uiState.copy(savePassword = savePassword)
    }

    // Load saved credentials and populate the form
    fun loadSavedCredentials(context: Context) {
        viewModelScope.launch {
            try {
                val savedUsername = ApiClient.getSavedUsername(context)
                val savedPassword = ApiClient.getSavedPassword(context)
                
                if (!savedUsername.isNullOrEmpty() && !savedPassword.isNullOrEmpty()) {
                    uiState = uiState.copy(
                        username = savedUsername,
                        password = savedPassword,
                        savePassword = true
                    )
                }
            } catch (e: Exception) {
                // Ignore errors when loading saved credentials
            }
        }
    }

    // Attempt auto-login if credentials are saved
    fun attemptAutoLogin(context: Context, onSuccess: () -> Unit) {
        viewModelScope.launch {
            try {
                val savedUsername = ApiClient.getSavedUsername(context)
                val savedPassword = ApiClient.getSavedPassword(context)
                val savedUrl = ApiClient.getBaseUrl(context)
                
                if (!savedUsername.isNullOrEmpty() && !savedPassword.isNullOrEmpty() && !savedUrl.isNullOrEmpty()) {
                    uiState = uiState.copy(
                        url = savedUrl,
                        username = savedUsername,
                        password = savedPassword,
                        savePassword = true
                    )
                    login(onSuccess)
                }
            } catch (e: Exception) {
                // Ignore errors during auto-login attempt
            }
        }
    }

    fun login(onSuccess: () -> Unit, secondFactorCode: String? = null) {
        login(null, onSuccess, secondFactorCode)
    }

    fun login(context: Context?, onSuccess: () -> Unit, secondFactorCode: String? = null) {
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
                    // Save credentials if checkbox is checked
                    if (uiState.savePassword && context != null) {
                        try {
                            ApiClient.saveCredentials(context, uiState.username, uiState.password)
                        } catch (e: Exception) {
                            // Continue with login even if saving credentials fails
                        }
                    }
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

    fun submitSecondFactor(context: Context?, onSuccess: () -> Unit) {
        login(context, onSuccess, uiState.secondFactorCode)
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

    // Reset all login state (used after logout)
    fun resetLoginState() {
        uiState = LoginUiState()
    }
}

data class LoginUiState(
    val url: String = "",
    val username: String = "",
    val password: String = "",
    val isLoading: Boolean = false,
    val error: String? = null,
    val showSecondFactorDialog: Boolean = false,
    val secondFactorCode: String = "",
    val savePassword: Boolean = false
)
