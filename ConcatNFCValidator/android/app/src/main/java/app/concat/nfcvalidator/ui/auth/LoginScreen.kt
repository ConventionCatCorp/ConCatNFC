package app.concat.nfcvalidator.ui.auth

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.window.Dialog
import app.concat.nfcvalidator.utils.ApiClient
import kotlinx.coroutines.launch

@Composable
fun LoginScreen(
    onLoginSuccess: () -> Unit,
    viewModel: LoginViewModel = viewModel()
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    
    // Get the current UI state
    val uiState = viewModel.uiState
    
    // Local state for form fields
    var url by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var savePassword by remember { mutableStateOf(false) }
    
    // Initialize form fields from ViewModel state
    LaunchedEffect(uiState) {
        if (url.isEmpty()) url = uiState.url
        if (username.isEmpty()) username = uiState.username
        if (password.isEmpty()) password = uiState.password
        if (!savePassword) savePassword = uiState.savePassword
        
        // Reset local state when ViewModel state is reset (after logout)
        if (uiState.url.isEmpty() && uiState.username.isEmpty() && uiState.password.isEmpty() && !uiState.savePassword) {
            url = ""
            username = ""
            password = ""
            savePassword = false
        }
    }
    
    // Load saved URL and attempt auto-login on first composition
    LaunchedEffect(Unit) {
        coroutineScope.launch {
            // Check if user just logged out first
            val justLoggedOut = ApiClient.hasJustLoggedOut(context)
            
            if (justLoggedOut) {
                // Clear the logout flag since we've shown the login screen
                ApiClient.clearLogoutFlag(context)
                // Reset ViewModel state to ensure clean form
                viewModel.resetLoginState()
                // Reset only credential-related local state variables (preserve URL)
                username = ""
                password = ""
                savePassword = false
                // Load the saved URL but don't load credentials
                val savedUrl = ApiClient.getBaseUrl(context)
                savedUrl?.let {
                    url = it
                    viewModel.updateUrl(it)
                }
                // Don't load any saved credentials or attempt auto-login
                return@launch
            }
            
            val savedUrl = ApiClient.getBaseUrl(context)
            savedUrl?.let {
                url = it
                viewModel.updateUrl(it)
            }
            
            // Load saved credentials
            viewModel.loadSavedCredentials(context)
            
            // Attempt auto-login if credentials are saved
            viewModel.attemptAutoLogin(context, onLoginSuccess)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // Title
        Text(
            text = "Login",
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.padding(bottom = 32.dp)
        )

        // Error message
        uiState.error?.let { error ->
            if (error.isNotBlank()) {
                Text(
                    text = error,
                    color = Color.Red,
                    modifier = Modifier.padding(bottom = 16.dp)
                )
            }
        }


        // URL field
        OutlinedTextField(
            value = url,
            onValueChange = { newUrl ->
                url = newUrl
                viewModel.updateUrl(newUrl)
            },
            label = { Text("Server URL") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            enabled = !uiState.isLoading,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            placeholder = { Text("https://example.com") },
            isError = !uiState.error.isNullOrBlank()
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Username field
        OutlinedTextField(
            value = username,
            onValueChange = { newUsername ->
                username = newUsername
                viewModel.updateUsername(newUsername)
            },
            label = { Text("Username or Email") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            enabled = !uiState.isLoading,
            isError = !uiState.error.isNullOrBlank()
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Password field
        OutlinedTextField(
            value = password,
            onValueChange = { newPassword ->
                password = newPassword
                viewModel.updatePassword(newPassword)
            },
            label = { Text("Password") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            enabled = !uiState.isLoading,
            isError = !uiState.error.isNullOrBlank()
        )

        // Save password checkbox
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Checkbox(
                checked = savePassword,
                onCheckedChange = { 
                    savePassword = it
                    viewModel.updateSavePassword(it)
                }
            )
            Text(
                text = "Save password",
                modifier = Modifier.padding(start = 8.dp)
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Login button
        Button(
            onClick = {
                coroutineScope.launch {
                    ApiClient.saveBaseUrl(context, url)
                    // Update viewModel with current form state
                    viewModel.updateSavePassword(savePassword)
                    viewModel.login(context, onLoginSuccess)
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp),
            enabled = !uiState.isLoading
        ) {
            if (uiState.isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    color = MaterialTheme.colorScheme.onPrimary,
                    strokeWidth = 2.dp
                )
            } else {
                Text("Login")
            }
        }
        
        // 2FA Dialog
        if (uiState.showSecondFactorDialog) {
            Dialog(onDismissRequest = { viewModel.showSecondFactorDialog(false) }) {
                Surface(
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.surface
                ) {
                    Column(
                        modifier = Modifier
                            .padding(16.dp)
                            .fillMaxWidth()
                    ) {
                        Text(
                            text = "Two-Factor Authentication",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(bottom = 16.dp)
                        )
                        
                        Text(
                            text = "Please enter the verification code from your authenticator app",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(bottom = 16.dp)
                        )
                        
                        OutlinedTextField(
                            value = uiState.secondFactorCode,
                            onValueChange = { viewModel.updateSecondFactorCode(it) },
                            label = { Text("Verification Code") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.fillMaxWidth()
                        )
                        
                        Spacer(modifier = Modifier.height(16.dp))
                        
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End
                        ) {
                            TextButton(
                                onClick = { viewModel.showSecondFactorDialog(false) },
                                modifier = Modifier.padding(end = 8.dp)
                            ) {
                                Text("Cancel")
                            }
                            Button(
                                onClick = { viewModel.submitSecondFactor(context, onLoginSuccess) },
                                enabled = uiState.secondFactorCode.length >= 6
                            ) {
                                Text("Verify")
                            }
                        }
                    }
                }
            }
        }
    }
}
