package org.odk.collect.android.recordwork

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import org.odk.collect.settings.SettingsProvider
import org.odk.collect.settings.keys.ProjectKeys
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import javax.net.ssl.HttpsURLConnection

/**
 * An authorized published version visible to the current enumerator for one
 * selected source record and one explicit workflow. Does not contain XForm XML.
 */
data class RecordTargetVersion(
    val targetFormVersionId: String,
    val formId: String,
    val formTitle: String,
    val version: String,
    val latest: Boolean,
    val sameFormAsSource: Boolean
)

/** Server-issued work only; a client-generated UUID is NOT a valid work ID. */
data class ServerRecordWork(
    val id: String,
    val sourceSubmissionId: String,
    val targetFormVersionId: String,
    val type: RecordWorkType,
    val visitKey: String?
)

/**
 * Only metadata is returned to the read-only preview. The raw submitted XML
 * and target XForm XML in the response are not displayed, cached or persisted.
 */
data class PreparedRecordPreview(
    val workId: String,
    val sourceSubmissionId: String,
    val targetFormVersionId: String,
    val targetFormId: String,
    val targetFormVersion: String,
    val type: RecordWorkType,
    val mappingApprovalRequired: Boolean
)

enum class PreparationError {
    INVALID_PROJECT, MISSING_CREDENTIALS, LOGIN_REQUIRED, ACCESS_DENIED, NOT_FOUND,
    CONFLICT, REDIRECT_BLOCKED, BAD_RESPONSE, SERVER_ERROR, NETWORK
}

class PreparationException(val reason: PreparationError) : IOException(reason.name)

/**
 * M4B-2 online-only record selection. Never opens an editor or transmits an
 * amendment/follow-up submission. M4C must implement the mapping approval
 * and JavaRosa checks before any existing record can become an editable draft.
 *
 * The same ODK project settings are used, without host-wide temporary auth.
 * Android's built-in HTTPS transport keeps the existing dependency lock.
 */
class RecordWorkPreparationClient(
    private val settings: SettingsProvider,
    private val openConnection: (URL) -> HttpURLConnection = {
        it.openConnection() as HttpsURLConnection
    }
) {
    fun targets(
        projectId: String, sourceSubmissionId: String, type: RecordWorkType
    ): List<RecordTargetVersion> {
        val session = session(projectId)
        val response = request(
            session,
            session.paths.targetVersions(sourceSubmissionId, type),
            method = "GET",
            expectedStatus = 200,
            maxSize = MAX_CATALOG
        )
        return parseOrReject {
            val array = JSONArray(response)
            require(array.length() <= 100) { "Unbounded candidate catalog" }
            (0 until array.length()).map { index ->
                val json = array.getJSONObject(index)
                val same = json.getBoolean("sameFormAsSource")
                if (type == RecordWorkType.AMENDMENT) {
                    require(same) { "Cross-form target for amendment" }
                }
                RecordTargetVersion(
                    targetFormVersionId = uuid(json.getString("targetFormVersionId")),
                    formId = uuid(json.getString("formId")),
                    formTitle = json.getString("formTitle"),
                    version = json.getString("version").also { require(it.isNotBlank()) },
                    latest = json.getBoolean("latest"),
                    sameFormAsSource = same
                )
            }.distinctBy { it.targetFormVersionId }
        }
    }

    fun create(projectId: String, request: NewRecordWork): ServerRecordWork {
        val session = session(projectId)
        val body = JSONObject()
            .put("sourceSubmissionId", request.sourceSubmissionId)
            .put("targetFormVersionId", request.targetFormVersionId)
            .put("workType", request.workType.name)
            .put("clientRequestId", request.clientRequestId)
            .put("visitKey", request.visitKey ?: JSONObject.NULL)
            .put("plannedFor", request.plannedFor ?: JSONObject.NULL)
            .toString()

        val response = request(session, session.paths.workItems(), "POST", 201, MAX_CATALOG, body)
        return parseOrReject {
            val json = JSONObject(response)
            val server = ServerRecordWork(
                id = uuid(json.getString("id")),
                sourceSubmissionId = uuid(json.getString("sourceSubmissionId")),
                targetFormVersionId = uuid(json.getString("targetFormVersionId")),
                type = RecordWorkType.valueOf(json.getString("workType")),
                visitKey = if (json.isNull("visitKey")) null else json.getString("visitKey")
            )
            require(json.getString("state") == "READY") { "Server work is not ready" }
            require(server.sourceSubmissionId == request.sourceSubmissionId &&
                server.targetFormVersionId == request.targetFormVersionId &&
                server.type == request.workType &&
                server.visitKey == request.visitKey) { "Server work does not match requested source and target" }
            server
        }
    }

    fun prepare(
        projectId: String, workId: String, expectedSource: String,
        expectedTargetVersion: String, expectedType: RecordWorkType
    ): PreparedRecordPreview {
        val session = session(projectId)
        val response = request(
            session, session.paths.prepare(workId), "GET", 200, MAX_PREPARE
        )
        return parseOrReject {
            val json = JSONObject(response)
            val work = json.getJSONObject("work")
            require(json.getBoolean("mappingApprovalRequired")) {
                "Form mapping approval must be required"
            }
            require(work.getString("state") == "READY") { "Work is not ready" }
            require(uuid(work.getString("id")) == uuid(workId)) { "Wrong work item" }
            require(uuid(work.getString("sourceSubmissionId")) == uuid(expectedSource)) {
                "Source record was changed"
            }
            require(uuid(work.getString("targetFormVersionId")) ==
                uuid(expectedTargetVersion)) { "Target form changed" }
            val actualType = RecordWorkType.valueOf(work.getString("workType"))
            require(actualType == expectedType) { "Work semantics changed" }
            require(json.getString("sourceXml").isNotBlank() &&
                json.getString("targetXFormXml").isNotBlank()) {
                "Missing exact source XML or target XForm"
            }
            // The next milestone must parse these XML documents under their
            // respective versions, with explicit field/repeat/choice/media review.
            PreparedRecordPreview(
                workId = workId,
                sourceSubmissionId = expectedSource,
                targetFormVersionId = expectedTargetVersion,
                targetFormId = json.getString("targetFormId"),
                targetFormVersion = json.getString("targetFormVersion"),
                type = actualType,
                mappingApprovalRequired = true
            )
        }
    }

    private data class Session(
        val paths: RecordWorkApiPaths, val username: String, val password: String
    )

    private fun session(projectId: String): Session {
        val project = settings.getUnprotectedSettings(projectId)
        val serverUrl = project.getString(ProjectKeys.KEY_SERVER_URL).orEmpty()
        val paths = try {
            RecordWorkApiPaths.fromProjectUrl(serverUrl)
        } catch (_: IllegalArgumentException) {
            throw PreparationException(PreparationError.INVALID_PROJECT)
        }
        val username = project.getString(ProjectKeys.KEY_USERNAME).orEmpty()
        val password = project.getString(ProjectKeys.KEY_PASSWORD).orEmpty()
        if (username.isBlank() || password.isBlank() ||
            username.any { it == ':' || it == '\n' || it == '\r' } ||
            password.any { it == '\n' || it == '\r' }) {
            throw PreparationException(PreparationError.MISSING_CREDENTIALS)
        }
        return Session(paths, username, password)
    }

    private fun request(
        session: Session, endpoint: String, method: String,
        expectedStatus: Int, maxSize: Int, body: String? = null
    ): String {
        val connection = try {
            openConnection(URL(endpoint))
        } catch (_: Exception) {
            throw PreparationException(PreparationError.NETWORK)
        }
        try {
            connection.instanceFollowRedirects = false
            connection.useCaches = false
            connection.connectTimeout = 20_000
            connection.readTimeout = 30_000
            connection.requestMethod = method
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Cache-Control", "no-store")
            val encoded = Base64.encodeToString(
                "${session.username}:${session.password}".toByteArray(StandardCharsets.UTF_8),
                Base64.NO_WRAP
            )
            connection.setRequestProperty("Authorization", "Basic $encoded")
            if (method == "POST") {
                require(body != null) { "POST body required" }
                val bytes = body.toByteArray(StandardCharsets.UTF_8)
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                connection.setFixedLengthStreamingMode(bytes.size)
                connection.outputStream.use { it.write(bytes) }
            }
            when (connection.responseCode) {
                expectedStatus -> Unit
                301, 302, 303, 307, 308 -> throw PreparationException(PreparationError.REDIRECT_BLOCKED)
                401 -> throw PreparationException(PreparationError.LOGIN_REQUIRED)
                403 -> throw PreparationException(PreparationError.ACCESS_DENIED)
                404 -> throw PreparationException(PreparationError.NOT_FOUND)
                409 -> throw PreparationException(PreparationError.CONFLICT)
                else -> throw PreparationException(PreparationError.SERVER_ERROR)
            }
            if (!connection.contentType?.substringBefore(';')?.trim()
                    .equals("application/json", ignoreCase = true)) {
                throw PreparationException(PreparationError.BAD_RESPONSE)
            }
            return connection.inputStream.use { stream ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val read = stream.read(buffer)
                    if (read < 0) break
                    if (output.size() + read > maxSize) {
                        throw PreparationException(PreparationError.BAD_RESPONSE)
                    }
                    output.write(buffer, 0, read)
                }
                output.toString(StandardCharsets.UTF_8.name())
            }
        } catch (ex: PreparationException) {
            throw ex
        } catch (_: IOException) {
            throw PreparationException(PreparationError.NETWORK)
        } finally {
            connection.disconnect()
        }
    }

    private fun <T> parseOrReject(block: () -> T): T {
        return try {
            block()
        } catch (_: Exception) {
            throw PreparationException(PreparationError.BAD_RESPONSE)
        }
    }

    companion object {
        private const val MAX_CATALOG = 1_048_576
        private const val MAX_PREPARE = 8_388_608
        private val UUID_TEXT = Regex(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"
        )

        private fun uuid(text: String): String {
            require(UUID_TEXT.matches(text)) { "Invalid UUID" }
            return text
        }
    }
}
