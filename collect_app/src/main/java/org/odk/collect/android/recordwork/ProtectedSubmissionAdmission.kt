package org.odk.collect.android.recordwork

import org.odk.collect.settings.SettingsProvider
import org.odk.collect.settings.keys.ProjectKeys
import java.io.File

/**
 * Point-in-time admission evidence, NOT a bearer token or upload capability.
 * The future sender must repeat admission against the current account and
 * exact package inside its own transport boundary. No credentials or XML
 * are returned and this class performs GET requests only.
 */
internal data class ProtectedSubmissionAdmissionEvidence(
    val projectId: String,
    val actorContextSha256: String,
    val workId: String,
    val sourceSubmissionId: String,
    val sourceInstanceId: String,
    val targetFormVersionId: String,
    val targetFormId: String,
    val targetVersion: String,
    val visitKey: String?,
    val reservedInstanceId: String,
    val generation: String,
    val packageSha256: String,
    val ciphertextSha256: String,
    val endpoint: String,
    val workHeaderName: String,
    val workHeaderValue: String,
    val canUpload: Boolean = false
) {
    init { require(!canUpload) }
}

internal class ProtectedSubmissionAdmission(
    private val scratch: File,
    private val root: File,
    private val settings: SettingsProvider
) {
    fun inspect(
        projectId: String, binding: QuarantinedWorkDraft,
        expectedGeneration: String, expectedPackageSha256: String
    ): ProtectedSubmissionAdmissionEvidence {
        require(expectedPackageSha256.matches(Regex("[0-9a-f]{64}")))
        require(binding.projectId == projectId && ProtectedReentryWork.eligible(binding))
        val endpoint = checkedEndpoint(projectId, binding)
        val store = ProtectedCommittedDraftStore(scratch, root, settings)
        val persisted = store.reopenFinalizedPackage(projectId, binding, expectedGeneration)
        val eligible = persisted.evidence.eligibility
        require(!persisted.canUpload && !eligible.canUpload &&
            eligible.workId == binding.workId && eligible.generation == expectedGeneration &&
            eligible.targetFormVersionId == binding.targetFormVersionId &&
            eligible.reservedInstanceId == binding.reservedInstanceId &&
            persisted.evidence.packaged.packageSha256 == expectedPackageSha256)
        // Independently reconcile visible original and current READY work;
        // an already-visible reserved instance is a recovery case, not a
        // fresh submission. Absence in a bounded listing is not proof of
        // global uniqueness: the backend must still enforce it at POST.
        verifySnapshot(binding, RecordWorkReadOnlyClient(settings).load(projectId))
        require(checkedEndpoint(projectId, binding) == endpoint)
        require(store.reopenFinalizedPackage(projectId, binding, expectedGeneration) == persisted)
        require(checkedEndpoint(projectId, binding) == endpoint)
        return ProtectedSubmissionAdmissionEvidence(projectId, binding.actorContextSha256,
            binding.workId, binding.sourceSubmissionId, binding.sourceInstanceId,
            binding.targetFormVersionId, binding.targetFormId, binding.targetVersion,
            binding.visitKey, binding.reservedInstanceId, expectedGeneration,
            expectedPackageSha256, persisted.ciphertextSha256, endpoint,
            RecordWorkCompletionGate.WORK_ITEM_HEADER, binding.workId)
    }

    private fun checkedEndpoint(projectId: String, binding: QuarantinedWorkDraft): String {
        val values = settings.getUnprotectedSettings(projectId)
        val paths = RecordWorkApiPaths.fromProjectUrl(values.getString(ProjectKeys.KEY_SERVER_URL).orEmpty())
        val username = values.getString(ProjectKeys.KEY_USERNAME).orEmpty()
        val password = values.getString(ProjectKeys.KEY_PASSWORD).orEmpty()
        require(username.isNotBlank() && password.isNotBlank() &&
            username.none { it == ':' || it == '\n' || it == '\r' } &&
            password.none { it == '\n' || it == '\r' })
        val scope = QuarantinedWorkDraftVault.sha256(projectId + "\n" + paths.records() + "\n" + username)
        require(scope == binding.actorContextSha256 &&
            QuarantinedWorkDraftVault(root).read(projectId, binding.workId, scope) == binding)
        return paths.submission()
    }

    companion object {
        internal fun verifySnapshot(binding: QuarantinedWorkDraft, snapshot: RemoteRecordSnapshot) {
            val source = snapshot.records.single { it.submissionId == binding.sourceSubmissionId }
            require(source.instanceId == binding.sourceInstanceId &&
                snapshot.records.none { it.instanceId == binding.reservedInstanceId } &&
                (binding.workType != RecordWorkType.AMENDMENT || source.mayOfferAmendment))
            val pending = snapshot.pendingWork.single { it.id == binding.workId }
            require(pending.sourceSubmissionId == binding.sourceSubmissionId &&
                pending.targetFormVersionId == binding.targetFormVersionId &&
                pending.type == binding.workType && pending.visitKey == binding.visitKey)
        }
    }
}
