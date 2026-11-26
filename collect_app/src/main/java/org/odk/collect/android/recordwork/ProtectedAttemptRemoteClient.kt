package org.odk.collect.android.recordwork

import android.util.Base64
import org.json.JSONObject
import org.json.JSONTokener
import org.odk.collect.settings.SettingsProvider
import org.odk.collect.settings.keys.ProjectKeys
import java.io.ByteArrayOutputStream
import java.net.URL
import javax.net.ssl.HttpsURLConnection

internal data class RemoteProtectedAttempt(
    val workId: String, val attemptId: String, val clientRequestId: String,
    val projectId: String, val actorId: String, val sourceId: String,
    val targetVersionId: String, val formId: String, val instanceId: String,
    val wireSha256: String?, val acceptedSubmissionId: String?
) {
    companion object {
        internal fun parse(obj: JSONObject): RemoteProtectedAttempt {
            require(obj.keys().asSequence().toSet() == setOf("workId", "attemptId", "clientRequestId",
                "projectId", "actorId", "sourceId", "targetVersionId", "formId", "instanceId",
                "wireSha256", "acceptedSubmissionId")) { "U08_ATTEMPT_JSON_SHAPE" }
            fun id(key: String) = ProtectedWireCommitment.uuid(obj.get(key) as String)
            val wire = if (obj.isNull("wireSha256")) null else (obj.get("wireSha256") as String).also {
                require(it.matches(Regex("[0-9a-f]{64}")))
            }
            val accepted = if (obj.isNull("acceptedSubmissionId")) null else id("acceptedSubmissionId")
            require(accepted == null || wire != null)
            val instance = obj.get("instanceId") as String
            require(instance.startsWith("uuid:"))
            ProtectedWireCommitment.uuid(instance.removePrefix("uuid:"))
            return RemoteProtectedAttempt(id("workId"), id("attemptId"), id("clientRequestId"),
                id("projectId"), id("actorId"), id("sourceId"), id("targetVersionId"), id("formId"),
                instance, wire, accepted)
        }
    }
}

internal enum class RemoteProtectedAttemptState {
    RESERVED_UNBOUND, BOUND_EXACT_CONTENT_ADMISSIBLE, COMPLETED_VERIFIED
}

internal data class RemoteProtectedAttemptStatus(
    val state: RemoteProtectedAttemptState, val attempt: RemoteProtectedAttempt,
    val completion: JSONObject?
) {
    companion object {
        internal fun parse(obj: JSONObject): RemoteProtectedAttemptStatus {
            require(obj.keys().asSequence().toSet() == setOf("state", "attempt", "completion")) {
                "U08_STATUS_JSON_SHAPE"
            }
            val state = RemoteProtectedAttemptState.valueOf(obj.get("state") as String)
            val attempt = RemoteProtectedAttempt.parse(obj.getJSONObject("attempt"))
            val completion = if (obj.isNull("completion")) null else obj.getJSONObject("completion")
            require(when (state) {
                RemoteProtectedAttemptState.RESERVED_UNBOUND ->
                    attempt.wireSha256 == null && attempt.acceptedSubmissionId == null && completion == null
                RemoteProtectedAttemptState.BOUND_EXACT_CONTENT_ADMISSIBLE ->
                    attempt.wireSha256 != null && attempt.acceptedSubmissionId == null && completion == null
                RemoteProtectedAttemptState.COMPLETED_VERIFIED ->
                    attempt.wireSha256 != null && attempt.acceptedSubmissionId != null && completion != null
            })
            return RemoteProtectedAttemptStatus(state, attempt, completion)
        }
    }
}

/** Fixed status code only; never carry a server body or credential. */
internal class ProtectedAttemptHttpException(val code: Int) :
    IllegalArgumentException("U08_HTTP_STATUS $code")

/** Explicit HTTPS reserve, bind and status only. Never sends a submission. */
internal class ProtectedAttemptRemoteClient(
    private val settings: SettingsProvider,
    private val openConnection: (URL) -> HttpsURLConnection = { it.openConnection() as HttpsURLConnection }
) {
    private data class Account(val base: String, val username: String, val password: String)

    private fun account(b: QuarantinedWorkDraft): Account {
        val values = settings.getUnprotectedSettings(b.projectId)
        val base = values.getString(ProjectKeys.KEY_SERVER_URL).orEmpty()
        val paths = RecordWorkApiPaths.fromProjectUrl(base)
        val user = values.getString(ProjectKeys.KEY_USERNAME).orEmpty()
        val password = values.getString(ProjectKeys.KEY_PASSWORD).orEmpty()
        require(b.workType == RecordWorkType.FOLLOW_UP && b.validationTransfers == 0 &&
            !b.requiresDeprecatedId && !b.visitKey.isNullOrBlank() && user.isNotBlank() && password.isNotBlank() &&
            user.none { it == ':' || it.isISOControl() } && password.none { it.isISOControl() } &&
            QuarantinedWorkDraftVault.sha256(b.projectId + "\n" + paths.records() + "\n" + user) ==
                b.actorContextSha256)
        return Account(base, user, password)
    }

    internal fun serverProjectId(b: QuarantinedWorkDraft): String =
        ProtectedWireCommitment.uuid(RecordWorkApiPaths.fromProjectUrl(account(b).base).serverProjectId())

    internal fun reserve(b: QuarantinedWorkDraft, requestId: String): RemoteProtectedAttempt =
        RemoteProtectedAttempt.parse(call(b, { it.protectedAttempt(b.workId) },
            JSONObject().put("clientRequestId", ProtectedWireCommitment.uuid(requestId))
                .put("instanceId", b.reservedInstanceId)))

    internal fun bind(b: QuarantinedWorkDraft, attemptId: String, wire: String): RemoteProtectedAttempt {
        require(wire.matches(Regex("[0-9a-f]{64}")))
        return RemoteProtectedAttempt.parse(call(b, { it.protectedCommitment(b.workId, attemptId) },
            JSONObject().put("wireSha256", wire)))
    }

    internal fun status(b: QuarantinedWorkDraft, attemptId: String): RemoteProtectedAttemptStatus =
        RemoteProtectedAttemptStatus.parse(call(b, { it.protectedAttempt(b.workId, attemptId) }, null))

    private fun call(b: QuarantinedWorkDraft, endpoint: (RecordWorkApiPaths) -> String, body: JSONObject?): JSONObject {
        val before = account(b)
        val url = endpoint(RecordWorkApiPaths.fromProjectUrl(before.base))
        val connection = openConnection(URL(url))
        try {
            connection.requestMethod = if (body == null) "GET" else "POST"
            connection.instanceFollowRedirects = false
            connection.useCaches = false
            connection.connectTimeout = 20_000
            connection.readTimeout = 20_000
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Cache-Control", "no-store")
            connection.setRequestProperty("Authorization", "Basic " + Base64.encodeToString(
                "${before.username}:${before.password}".toByteArray(Charsets.UTF_8), Base64.NO_WRAP))
            if (body != null) {
                val bytes = body.toString().toByteArray(Charsets.UTF_8)
                require(bytes.size <= 4096)
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.setFixedLengthStreamingMode(bytes.size)
                connection.outputStream.use { it.write(bytes) }
            }
            // No fallback on 404, no redirect, and no response-body exception.
            val responseCode = connection.responseCode
            if (responseCode != 200) throw ProtectedAttemptHttpException(responseCode)
            require(connection.contentType?.substringBefore(';')?.trim().equals("application/json", true)) {
                "U08_RESPONSE_MEDIA_TYPE"
            }
            require(connection.getHeaderField("Cache-Control").orEmpty().contains("no-store", true)) {
                "U08_RESPONSE_CACHE_POLICY"
            }
            val output = ByteArrayOutputStream()
            connection.inputStream.use { input ->
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    require(output.size() + count <= 1_048_576)
                    output.write(buffer, 0, count)
                }
            }
            val text = Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(output.toByteArray())).toString()
            val tokener = JSONTokener(text)
            val result = tokener.nextValue() as JSONObject
            require(tokener.nextClean() == '\u0000' && account(b) == before)
            return result
        } finally {
            connection.disconnect()
        }
    }
}
