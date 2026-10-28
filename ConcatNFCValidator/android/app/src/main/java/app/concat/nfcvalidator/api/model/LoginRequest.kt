package app.concat.nfcvalidator.api.model

data class LoginRequest(
    val usernameOrMail: String,
    val password: String,
    var secondFactor: SecondFactor? = null
)

// Server requires exactly one of otpCode or backupCode; Gson omits the null one
data class SecondFactor(
    val otpCode: String? = null,
    val backupCode: String? = null
)
