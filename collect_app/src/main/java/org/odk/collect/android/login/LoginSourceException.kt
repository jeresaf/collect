package org.odk.collect.android.login

sealed class LoginSourceException : Exception() {
    class Unreachable(val serverUrl: String) : LoginSourceException()
    class AuthRequired : LoginSourceException()
    class FetchError : LoginSourceException()
    class SecurityError(val serverUrl: String) : LoginSourceException()
    class ServerError(val statusCode: Int, val serverUrl: String) : LoginSourceException()
    class ParseError(val serverUrl: String) : LoginSourceException()
    class UserNotAllowedAccess : LoginSourceException()

    // Aggregate 0.9 and prior used a custom API before the OpenRosa standard was in place. Aggregate continued
    // to provide this response to HTTP requests so some custom servers tried to implement it.
    class ServerNotOpenRosaError : LoginSourceException()
}
