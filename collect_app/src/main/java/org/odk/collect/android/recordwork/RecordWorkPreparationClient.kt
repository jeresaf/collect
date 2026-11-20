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
    val mappingApprovalRequired: Boolean,
    // M4C-1: risk metadata only, NEVER raw answers or a form instance.
    val assessment: CrossVersionAssessment? = null,
    // M4C-2A: versioned path proposals, no source answers and no edit authority.
    val mappingPlan: HistoricalMappingPlan? = null,
    // M4C-2B-1: real JavaRosa blank form initialized but no editable draft.
    val nativeStaging: NativeStagingEvidence? = null,
    // M4C-2B-3: verified server identifiers for quarantined draft bindings.
    // Null for older synthetic fixture responses; staging fails closed.
    val sourceInstanceId: String? = null,
    val visitKey: String? = null
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
    // Optional per-request native check, with no network access or answers.
    // Keep openConnection last to preserve existing mocked transport tests.
    private val nativeProbe: ((
        HistoricalMappingPlan, String, String?
    ) -> NativeStagingEvidence)? = null,
    private val transferProbe: ((
        HistoricalMappingPlan, MappingReviewSnapshot, String, String,
        String, String?, String
    ) -> ReviewedTransferEvidence)? = null,
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
        expectedTargetVersion: String, expectedType: RecordWorkType,
        assessCrossVersion: Boolean = false
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
            val preview = PreparedRecordPreview(
                workId = workId,
                sourceSubmissionId = expectedSource,
                targetFormVersionId = expectedTargetVersion,
                targetFormId = json.getString("targetFormId"),
                targetFormVersion = json.getString("targetFormVersion"),
                type = actualType,
                mappingApprovalRequired = true,
                sourceInstanceId = work.optString("sourceInstanceId").takeIf(String::isNotBlank),
                visitKey = work.optString("visitKey").takeIf {
                    it.isNotBlank() && it != "null"
                },
                assessment = if (assessCrossVersion) {
                    CrossVersionAssessmentEngine.assess(
                        workId = workId,
                        sourceSubmissionId = expectedSource,
                        targetFormVersionId = expectedTargetVersion,
                        workType = actualType,
                        sourceXml = json.getString("sourceXml"),
                        targetXFormXml = json.getString("targetXFormXml"),
                        expectedTargetFormId = json.getString("targetFormId"),
                        expectedTargetVersion = json.getString("targetFormVersion")
                    )
                } else null,
                mappingPlan = if (assessCrossVersion) {
                    HistoricalSchemaMappingEngine.plan(
                        workId = workId,
                        sourceSubmissionId = expectedSource,
                        targetFormVersionId = expectedTargetVersion,
                        workType = actualType,
                        sourceXml = json.getString("sourceXml"),
                        // Earlier M3 versions legitimately return no historical
                        // definition. Never substitute the current target form.
                        sourceXFormXml = if (json.isNull("sourceXFormXml")) null
                            else json.getString("sourceXFormXml"),
                        targetXFormXml = json.getString("targetXFormXml"),
                        targetFormId = json.getString("targetFormId"),
                        targetFormVersion = json.getString("targetFormVersion")
                    )
                } else null
            )
            if (assessCrossVersion && nativeProbe != null) {
                val exactSourceForm = if (json.isNull("sourceXFormXml")) null
                    else json.getString("sourceXFormXml")
                val plan = requireNotNull(preview.mappingPlan)
                val native = nativeProbe.invoke(
                    plan,
                    json.getString("targetXFormXml"),
                    exactSourceForm
                )
                require(native.workId == preview.workId &&
                    native.targetFormVersionId == preview.targetFormVersionId &&
                    native.targetXFormSha256 == plan.targetXFormSha256 &&
                    native.targetJavaRosaInitialized) {
                    "Native validation must match the exact prepared target"
                }
                preview.copy(nativeStaging = native)
            } else preview
        }
    }

    /**
     * Re-fetches the READY work item, re-checks all server identities and
     * original/target bytes, then validates the operator's explicit mapping
     * in an ephemeral native model. No draft/save/upload API is exposed.
     */
    fun validateReviewedTransfer(
        projectId: String,
        previouslyPrepared: PreparedRecordPreview,
        review: MappingReviewSnapshot
    ): ReviewedTransferEvidence {
        val validator = transferProbe
            ?: throw PreparationException(PreparationError.BAD_RESPONSE)
        val previousPlan = previouslyPrepared.mappingPlan
            ?: throw PreparationException(PreparationError.BAD_RESPONSE)
        val previousAssessment = previouslyPrepared.assessment
            ?: throw PreparationException(PreparationError.BAD_RESPONSE)
        val paths = session(projectId)
        val response = request(
            paths, paths.paths.prepare(previouslyPrepared.workId),
            "GET", 200, MAX_PREPARE
        )
        return parseOrReject {
            val json = JSONObject(response)
            val work = json.getJSONObject("work")
            require(json.getBoolean("mappingApprovalRequired")) {
                "Mapping approval is a server-required state"
            }
            require(work.getString("state") == "READY" &&
                uuid(work.getString("id")) == previouslyPrepared.workId &&
                uuid(work.getString("sourceSubmissionId")) == previouslyPrepared.sourceSubmissionId &&
                uuid(work.getString("targetFormVersionId")) ==
                    previouslyPrepared.targetFormVersionId &&
                RecordWorkType.valueOf(work.getString("workType")) ==
                    previouslyPrepared.type) {
                "Work item changed after operator review"
            }
            require(previouslyPrepared.type == RecordWorkType.AMENDMENT) {
                "Independent follow-up cannot copy old answers"
            }
            val sourceXml = json.getString("sourceXml")
            val targetXml = json.getString("targetXFormXml")
            val sourceForm = if (json.isNull("sourceXFormXml")) null
                else json.getString("sourceXFormXml")
            val newPlan = HistoricalSchemaMappingEngine.plan(
                workId = previouslyPrepared.workId,
                sourceSubmissionId = previouslyPrepared.sourceSubmissionId,
                targetFormVersionId = previouslyPrepared.targetFormVersionId,
                workType = previouslyPrepared.type,
                sourceXml = sourceXml,
                sourceXFormXml = sourceForm,
                targetXFormXml = targetXml,
                targetFormId = json.getString("targetFormId"),
                targetFormVersion = json.getString("targetFormVersion")
            )
            require(newPlan == previousPlan) {
                "Form schemas or mapping changed after review"
            }
            val newAssessment = CrossVersionAssessmentEngine.assess(
                workId = previouslyPrepared.workId,
                sourceSubmissionId = previouslyPrepared.sourceSubmissionId,
                targetFormVersionId = previouslyPrepared.targetFormVersionId,
                workType = previouslyPrepared.type,
                sourceXml = sourceXml,
                targetXFormXml = targetXml,
                expectedTargetFormId = json.getString("targetFormId"),
                expectedTargetVersion = json.getString("targetFormVersion")
            )
            require(newAssessment.sourceSha256 == previousAssessment.sourceSha256 &&
                newAssessment.targetXFormSha256 == previousAssessment.targetXFormSha256) {
                "Original submitted answers changed after review"
            }
            val serverInstanceId = work.getString("sourceInstanceId")
            val checked = validator(
                newPlan, review, sourceXml, newAssessment.sourceSha256,
                serverInstanceId, sourceForm, targetXml
            )
            require(checked.workId == previouslyPrepared.workId &&
                checked.sourceSubmissionId == previouslyPrepared.sourceSubmissionId &&
                checked.targetFormVersionId == previouslyPrepared.targetFormVersionId &&
                checked.sourceXmlSha256 == previousAssessment.sourceSha256 &&
                checked.sourceInstanceId == serverInstanceId &&
                checked.pathsReviewed == newPlan.candidates.size &&
                !checked.canSaveDraft && !checked.canSubmit) {
                "Transfer validation is not bound to the exact server work item"
            }
            checked
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
