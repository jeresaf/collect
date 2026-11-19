package org.odk.collect.android.recordwork

import okhttp3.Credentials
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.odk.collect.settings.SettingsProvider
import org.odk.collect.settings.keys.ProjectKeys
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

/** Server-owned records, never local ODK draft/instance identities. */
data class RemoteRecord(
    val submissionId: String,
    val instanceId: String,
    val formId: String,
    val formVersionId: String?,
    val submittedAt: String
)

data class RemoteVisitWork(
    val id: String,
    val sourceSubmissionId: String,
    val targetFormVersionId: String,
    val type: RecordWorkType,
    val visitKey: String?
)

data class RemoteRecordSnapshot(
    val records: List<RemoteRecord>,
    val pendingWork: List<RemoteVisitWork>
)

enum class ReadOnlyRequestFailure {
    INVALID_PROJECT, NO_CREDENTIALS, AUTH_REQUIRED, ACCESS_DENIED,
    NOT_SUPPORTED, REDIRECT_REFUSED, SERVER_ERROR, INVALID_RESPONSE, NETWORK_ERROR
}

class ReadOnlyRecordException(val reason: ReadOnlyRequestFailure) : IOException(reason.name)

/**
 * M4B-1: read-only, online record retrieval using the *existing project's*
 * ODK username/password settings, with no secondary credential store.
 *
 * Deliberately uses a redirect-disabled, cache-free OkHttpClient rather than
 * the permissive form-download redirect policy: the sensitive Basic header
 * must never be forwarded to another origin. No server XML is downloaded.
 */
class RecordWorkReadOnlyClient(
    private val settingsProvider: SettingsProvider,
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .followRedirects(false)
        .followSslRedirects(false)
        .cache(null)
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()
) {
    fun load(projectId: String): RemoteRecordSnapshot {
        val settings = settingsProvider.getUnprotectedSettings(projectId)
        val serverUrl = settings.getString(ProjectKeys.KEY_SERVER_URL).orEmpty()
        val paths = try {
            RecordWorkApiPaths.fromProjectUrl(serverUrl)
        } catch (_: IllegalArgumentException) {
            throw ReadOnlyRecordException(ReadOnlyRequestFailure.INVALID_PROJECT)
        }
        // Read exactly the active project's ODK settings. Temporary host-wide
        // credentials from unrelated project sessions are intentionally ignored.
        val username = settings.getString(ProjectKeys.KEY_USERNAME).orEmpty()
        val password = settings.getString(ProjectKeys.KEY_PASSWORD).orEmpty()
        if (username.isBlank() || password.isBlank()) {
            throw ReadOnlyRecordException(ReadOnlyRequestFailure.NO_CREDENTIALS)
        }

        val recordsJson = fetch(paths.records(), username, password)
        val workJson = fetch(paths.workItems(), username, password)
        return try {
            RemoteRecordSnapshot(
                RecordWorkJsonParser.records(recordsJson),
                RecordWorkJsonParser.pending(workJson)
            )
        } catch (_: Exception) {
            throw ReadOnlyRecordException(ReadOnlyRequestFailure.INVALID_RESPONSE)
        }
    }

    private fun fetch(url: String, username: String, password: String): String {
        // The only allowed URLs are constructed from a validated active HTTPS
        // project base; callers cannot supply any arbitrary destination.
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .header("Cache-Control", "no-store")
            .header("Authorization", Credentials.basic(username, password))
            .get()
            .build()
        try {
            httpClient.newCall(request).execute().use { response ->
                when (response.code) {
                    200 -> Unit
                    301, 302, 303, 307, 308 ->
                        throw ReadOnlyRecordException(ReadOnlyRequestFailure.REDIRECT_REFUSED)
                    401 -> throw ReadOnlyRecordException(ReadOnlyRequestFailure.AUTH_REQUIRED)
                    403 -> throw ReadOnlyRecordException(ReadOnlyRequestFailure.ACCESS_DENIED)
                    404, 405 -> throw ReadOnlyRecordException(ReadOnlyRequestFailure.NOT_SUPPORTED)
                    else -> throw ReadOnlyRecordException(ReadOnlyRequestFailure.SERVER_ERROR)
                }

                val body = response.body
                    ?: throw ReadOnlyRecordException(ReadOnlyRequestFailure.INVALID_RESPONSE)
                val mime = body.contentType()
                if (mime?.type != "application" || mime?.subtype != "json") {
                    throw ReadOnlyRecordException(ReadOnlyRequestFailure.INVALID_RESPONSE)
                }
                val bytes = body.byteStream().use(::boundedRead)
                return String(bytes, StandardCharsets.UTF_8)
            }
        } catch (ex: ReadOnlyRecordException) {
            throw ex
        } catch (_: IOException) {
            throw ReadOnlyRecordException(ReadOnlyRequestFailure.NETWORK_ERROR)
        }
    }

    private fun boundedRead(stream: java.io.InputStream): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = stream.read(buffer)
            if (count < 0) break
            if (output.size() + count > MAX_BYTES) {
                throw ReadOnlyRecordException(ReadOnlyRequestFailure.INVALID_RESPONSE)
            }
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    companion object {
        private const val MAX_BYTES = 1_048_576
    }
}

/** The M3 backend limits both lists to 100. Reject unexpectedly huge arrays. */
object RecordWorkJsonParser {
    fun records(json: String): List<RemoteRecord> {
        val array = JSONArray(json)
        require(array.length() <= 100) { "Unexpected records count" }
        return (0 until array.length()).map { index ->
            val item = array.getJSONObject(index)
            RemoteRecord(
                submissionId = safeUuid(item.getString("submissionId")),
                instanceId = item.getString("instanceId").also { require(it.isNotBlank()) },
                formId = safeUuid(item.getString("formId")),
                formVersionId = if (item.isNull("formVersionId")) null else safeUuid(item.getString("formVersionId")),
                submittedAt = item.optString("submittedAt")
            )
        }
    }

    fun pending(json: String): List<RemoteVisitWork> {
        val array = JSONArray(json)
        require(array.length() <= 100) { "Unexpected pending work count" }
        return (0 until array.length()).map { index ->
            val item = array.getJSONObject(index)
            require(item.getString("state") == "READY") {
                "Only pending READY work items should be displayed"
            }
            val type = RecordWorkType.valueOf(item.getString("workType"))
            val visitKey = if (item.isNull("visitKey")) {
                null
            } else {
                item.getString("visitKey")
            }
            if (type == RecordWorkType.FOLLOW_UP) {
                require(!visitKey.isNullOrBlank()) { "Missing follow-up visit key" }
            }
            RemoteVisitWork(
                id = safeUuid(item.getString("id")),
                sourceSubmissionId = safeUuid(item.getString("sourceSubmissionId")),
                targetFormVersionId = safeUuid(item.getString("targetFormVersionId")),
                type = type,
                visitKey = visitKey
            )
        }
    }

    private val UUID_TEXT = Regex(
        "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"
    )

    private fun safeUuid(text: String): String {
        require(UUID_TEXT.matches(text)) { "Expected canonical UUID" }
        return text
    }
}
