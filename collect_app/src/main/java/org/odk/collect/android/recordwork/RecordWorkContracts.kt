package org.odk.collect.android.recordwork

import java.net.URI
import java.util.UUID

/**
 * M4A: Defensive, transport-independent contract for the Data Collector M3 API.
 *
 * This does not fetch credentials, import XForms, create/edit instances, or claim
 * that old answers are valid for a newer form. Native ODK transport, JavaRosa
 * prefill, local persistence and UI must be integrated in later slices.
 */
class RecordWorkApiPaths private constructor(private val projectBase: String) {
    fun records(): String = "$projectBase/records"
    fun timeline(sourceSubmissionId: String): String =
        "$projectBase/records/${checkedId(sourceSubmissionId)}/timeline"
    fun targetVersions(sourceSubmissionId: String, workType: RecordWorkType): String =
        "$projectBase/records/${checkedId(sourceSubmissionId)}/target-versions?workType=${workType.name}"
    fun workItems(): String = "$projectBase/record-work-items"
    fun prepare(workItemId: String): String =
        "$projectBase/record-work-items/${checkedId(workItemId)}/prepare"
    fun submission(): String = "$projectBase/submission"

    companion object {
        fun fromProjectUrl(projectUrl: String): RecordWorkApiPaths {
            val uri = try {
                URI(projectUrl)
            } catch (ex: Exception) {
                throw IllegalArgumentException("Invalid project URL", ex)
            }
            require(uri.scheme.equals("https", ignoreCase = false) && !uri.host.isNullOrBlank()) {
                "Record work requires the active HTTPS project URL"
            }
            require(uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null) {
                "No credentials, query or fragment may be embedded in a project URL"
            }
            val path = uri.rawPath?.removeSuffix("/") ?: ""
            require(path.startsWith("/v1/projects/")) { "Not a project-scoped OpenRosa URL" }
            checkedId(path.removePrefix("/v1/projects/"))
            require(uri.rawPath == path || uri.rawPath == "$path/") {
                "Unexpected project URL path"
            }
            return RecordWorkApiPaths(projectUrl.removeSuffix("/"))
        }
    }
}

enum class RecordWorkType { AMENDMENT, FOLLOW_UP }

/**
 * Request to create a server-owned work item. The server remains authoritative
 * for active App User, ownership, published target version and assignment.
 */
data class NewRecordWork(
    val sourceSubmissionId: String,
    val targetFormVersionId: String,
    val workType: RecordWorkType,
    val clientRequestId: String,
    val visitKey: String? = null,
    val plannedFor: String? = null
) {
    init {
        checkedId(sourceSubmissionId)
        checkedId(targetFormVersionId)
        require(TOKEN.matches(clientRequestId)) { "Invalid clientRequestId" }
        when (workType) {
            RecordWorkType.AMENDMENT ->
                require(visitKey == null && plannedFor == null) {
                    "An amendment is not a new visit"
                }
            RecordWorkType.FOLLOW_UP ->
                require(visitKey != null && TOKEN.matches(visitKey)) {
                    "A follow-up requires a stable visitKey"
                }
        }
    }
}

/**
 * Fields extracted from an authenticated M3 GET /record-work-items/{id}/prepare.
 * The original XML and target XForm must be retained separately and handled
 * by the appropriate JavaRosa importer; they are not interchangeable.
 */
data class PreparedRecordWork(
    val id: String,
    val sourceInstanceId: String,
    val sourceFormVersionId: String?,
    val targetFormVersionId: String,
    val workType: RecordWorkType,
    val state: String,
    val mappingApprovalRequired: Boolean
) {
    init {
        checkedId(id)
        sourceFormVersionId?.let(::checkedId)
        checkedId(targetFormVersionId)
        require(sourceInstanceId.isNotBlank()) { "Missing original instanceID" }
    }
}

/**
 * Positive evidence from an explicit client-side mapping and form review.
 * Each flag requires its OWN actual UI/JavaRosa check; never set these values
 * optimistically when parsing the prepare response.
 */
data class RecordMappingApproval(
    val workItemId: String,
    val sourceFormVersionId: String?,
    val targetFormVersionId: String,
    val fieldPathsReviewed: Boolean,
    val removedAndNewQuestionsReviewed: Boolean,
    val repeatsReviewed: Boolean,
    val choicesReviewed: Boolean,
    val attachmentsReviewed: Boolean,
    val targetXFormValidated: Boolean,
    val acceptedByEnumerator: Boolean
)

/**
 * Identity and submission header to use only AFTER the whole revised/new XForm
 * has been evaluated and accepted. Upload must include the work-item header.
 */
data class RecordWorkCompletion(
    val newInstanceId: String,
    val deprecatedId: String?,
    val workItemHeaderName: String,
    val workItemHeaderValue: String
)

object RecordWorkCompletionGate {
    const val WORK_ITEM_HEADER = "X-DataCollector-Work-Item-Id"

    fun authorize(
        prepared: PreparedRecordWork,
        approval: RecordMappingApproval,
        newInstanceId: String = "uuid:${UUID.randomUUID()}"
    ): RecordWorkCompletion {
        require(prepared.state == "READY") { "Only READY work items can be completed" }
        require(prepared.mappingApprovalRequired) {
            "Unexpected unreviewed mapping policy: refuse to prepare submission"
        }
        require(approval.workItemId == prepared.id &&
            approval.sourceFormVersionId == prepared.sourceFormVersionId &&
            approval.targetFormVersionId == prepared.targetFormVersionId) {
            "Review was not performed against this exact work item and form versions"
        }
        require(approval.fieldPathsReviewed &&
            approval.removedAndNewQuestionsReviewed &&
            approval.repeatsReviewed &&
            approval.choicesReviewed &&
            approval.attachmentsReviewed &&
            approval.targetXFormValidated &&
            approval.acceptedByEnumerator) {
            "Mapping review, target XForm validation and operator approval required"
        }
        require(newInstanceId.startsWith("uuid:")) { "New instanceID must use the uuid: prefix" }
        checkedId(newInstanceId.removePrefix("uuid:"))
        require(newInstanceId != prepared.sourceInstanceId) {
            "A new submission cannot reuse the source instanceID"
        }
        return RecordWorkCompletion(
            newInstanceId = newInstanceId,
            deprecatedId = if (prepared.workType == RecordWorkType.AMENDMENT) {
                prepared.sourceInstanceId
            } else {
                null
            },
            workItemHeaderName = WORK_ITEM_HEADER,
            workItemHeaderValue = prepared.id
        )
    }
}

private val UUID_PATTERN =
    Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
private val TOKEN = Regex("[A-Za-z0-9._:-]{1,128}")

private fun checkedId(value: String): String {
    require(UUID_PATTERN.matches(value)) { "Expected a canonical UUID" }
    UUID.fromString(value)
    return value
}
