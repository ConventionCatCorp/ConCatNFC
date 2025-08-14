package app.concat.nfcvalidator.api.model

data class SecondFactorError(
    val code: Int,
    val message: String,
    val secondFactor: Map<String, Any> = emptyMap()
)

data class AuthenticationError(
    val authentication: SecondFactorError
)

data class ErrorResponse(
    val errors: AuthenticationError
)
