package app.concat.nfcvalidator.api.model

data class LoginRequest(
    val usernameOrMail: String,
    val password: String,
    var secondFactor: SecondFactor? = null
)

data class SecondFactor(
    val code: String,
    val type: String = "otp"
)
