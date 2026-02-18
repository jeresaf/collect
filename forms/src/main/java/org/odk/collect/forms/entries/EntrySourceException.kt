package org.odk.collect.forms.entries

sealed class EntrySourceException : Exception() {
    class Unreachable(val serverUrl: String) : EntrySourceException()
    class AuthRequired : EntrySourceException()
    class FetchError : EntrySourceException()
    class SecurityError(val serverUrl: String) : EntrySourceException()
    class ServerError(val statusCode: Int, val serverUrl: String) : EntrySourceException()
    class ParseError(val serverUrl: String) : EntrySourceException()

    // Aggregate 0.9 and prior used a custom API before the OpenRosa standard was in place. Aggregate continued
    // to provide this response to HTTP requests so some custom servers tried to implement it.
    class ServerNotOpenRosaError : EntrySourceException()
}