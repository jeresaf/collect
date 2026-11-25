package org.odk.collect.android.recordwork

import org.odk.collect.settings.SettingsProvider
import org.odk.collect.settings.keys.ProjectKeys
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.util.UUID

/** Authenticated local receipt, not permission to edit, finalize or send. */
internal data class ProtectedSubmittedReceipt(
    val generation: String, val packageSha256: String, val ciphertextSha256: String,
    val attemptSha256: String, val instanceId: String, val submissionId: String,
    val targetFormVersionId: String,
    val canEdit: Boolean = false, val canUpload: Boolean = false
) {
    init { require(!canEdit && !canUpload) }
}

/** Read-only reconciliation evidence, never a receipt or permission to retry. */
internal enum class ProtectedReconciliationState {
    LINKED_RECORD_VISIBLE, READY_WITHOUT_VISIBLE_RECORD, INDETERMINATE, CONFLICT
}

internal data class ProtectedReconciliationEvidence(
    val state: ProtectedReconciliationState,
    val generation: String,
    val packageSha256: String,
    val attemptSha256: String,
    val submissionId: String? = null,
    val canEdit: Boolean = false,
    val canUpload: Boolean = false
) {
    init {
        require(!canEdit && !canUpload)
        require((state == ProtectedReconciliationState.LINKED_RECORD_VISIBLE) == (submissionId != null))
    }
}

/** Receipt publication after a verified 201 and authenticated server link.
 * No READY authorization is assumed after completion; no plaintext is saved.
 * Online reopen is mandatory, and missing/corrupt records fail closed.
 */
internal class ProtectedSubmissionReceiptStore(
    private val root: File, private val settings: SettingsProvider
) {
    private val cipher = AndroidKeyStorePrivateDraftCipher()
    private val digestPattern = Regex("[0-9a-f]{64}")

    internal fun recordAcknowledged(
        projectId: String, binding: QuarantinedWorkDraft, generation: String,
        result: ProtectedTransportResult
    ): ProtectedSubmittedReceipt = synchronized(ProtectedDraftComponentLock.monitor) {
        require(result.httpStatus == 201 && result.acknowledged &&
            result.instanceId == binding.reservedInstanceId)
        checkAccount(projectId, binding)
        val area = ProtectedMultipartSubmission.submissionAttemptArea(root, binding)
        val attempt = attempt(area, binding)
        require(attempt.generation == generation && attempt.packageSha256 == result.packageSha256)
        val child = reconcile(projectId, binding)
        checkAccount(projectId, binding)
        require(attempt(area, binding) == attempt)
        val receipt = attempt.copy(submissionId = child.submissionId)
        val target = File(area, "submitted-receipt.aesgcm")
        val pending = File(area, ".submitted-receipt-write")
        require(!Files.isSymbolicLink(target.toPath()) && !Files.isSymbolicLink(pending.toPath()))
        if (target.exists()) {
            require(readReceipt(target, binding) == receipt)
            return@synchronized receipt
        }
        // An incomplete pending receipt never counts as submitted. Only an
        // explicit acknowledged publication can remove an abandoned temp.
        if (pending.exists()) {
            checkedBytes(pending, 4096)
            require(pending.delete())
        }
        val clear = encode(receipt)
        try {
            val sealed = cipher.seal(clear, aad(binding, "DC_SUBMITTED_RECEIPT_V1"))
            require(pending.createNewFile())
            FileOutputStream(pending).use { it.write(sealed); it.fd.sync() }
            require(readReceipt(pending, binding) == receipt)
            require(!target.exists() && !Files.isSymbolicLink(target.toPath()) && pending.renameTo(target))
            require(readReceipt(target, binding) == receipt)
            receipt
        } finally { clear.fill(0) }
    }

    fun reopen(projectId: String, binding: QuarantinedWorkDraft): ProtectedSubmittedReceipt =
        synchronized(ProtectedDraftComponentLock.monitor) {
            checkAccount(projectId, binding)
            val area = ProtectedMultipartSubmission.submissionAttemptArea(root, binding)
            val file = File(area, "submitted-receipt.aesgcm")
            val receipt = readReceipt(file, binding)
            require(attempt(area, binding).copy(submissionId = receipt.submissionId) == receipt)
            val child = reconcile(projectId, binding)
            require(child.submissionId == receipt.submissionId)
            checkAccount(projectId, binding)
            require(readReceipt(file, binding) == receipt &&
                attempt(area, binding).copy(submissionId = receipt.submissionId) == receipt)
            receipt
        }

    /**
     * No write, no READY editor authorization, no POST and no automatic
     * acknowledgement. A bounded listing cannot prove server nonacceptance.
     * Corrupt/missing evidence or failed auth/network throws; callers must
     * preserve the attempt rather than interpret an exception as absence.
     */
    fun inspectAttempt(projectId: String, binding: QuarantinedWorkDraft): ProtectedReconciliationEvidence =
        synchronized(ProtectedDraftComponentLock.monitor) {
            checkAccount(projectId, binding)
            val area = ProtectedMultipartSubmission.submissionAttemptArea(root, binding)
            val original = attempt(area, binding)
            val remote = RecordWorkReadOnlyClient(settings)
            val snapshot = remote.load(projectId)
            val (state, child) = classifySnapshot(binding, snapshot)
            val finalState = if (state == ProtectedReconciliationState.LINKED_RECORD_VISIBLE &&
                !remote.completedVisit(projectId, binding, requireNotNull(child))) {
                ProtectedReconciliationState.CONFLICT
            } else state
            // Repeat the bounded snapshot; do not return a positive finding
            // across a changing server record or account context.
            val repeated = remote.load(projectId)
            require(snapshot.records.size == repeated.records.size &&
                snapshot.pendingWork.size == repeated.pendingWork.size &&
                snapshot.records.toSet() == repeated.records.toSet() &&
                snapshot.pendingWork.toSet() == repeated.pendingWork.toSet()) {
                "Server record context changed during reconciliation"
            }
            checkAccount(projectId, binding)
            require(attempt(area, binding) == original)
            ProtectedReconciliationEvidence(finalState, original.generation,
                original.packageSha256, original.attemptSha256,
                if (finalState == ProtectedReconciliationState.LINKED_RECORD_VISIBLE) child?.submissionId else null)
        }

    companion object {
        /** Metadata classification only; LINKED requires separate timeline corroboration. */
        internal fun classifySnapshot(b: QuarantinedWorkDraft, snapshot: RemoteRecordSnapshot):
            Pair<ProtectedReconciliationState, RemoteRecord?> {
            val records = snapshot.records
            val pending = snapshot.pendingWork
            val conflict = ProtectedReconciliationState.CONFLICT to null
            val unknown = ProtectedReconciliationState.INDETERMINATE to null
            if (records.map { it.submissionId }.toSet().size != records.size ||
                pending.map { it.id }.toSet().size != pending.size) return conflict
            val source = records.singleOrNull { it.submissionId == b.sourceSubmissionId }
                ?: return unknown
            if (source.instanceId != b.sourceInstanceId) return conflict
            val children = records.filter { it.instanceId == b.reservedInstanceId }
            val work = pending.singleOrNull { it.id == b.workId }
            if (work != null && (work.sourceSubmissionId != b.sourceSubmissionId ||
                    work.targetFormVersionId != b.targetFormVersionId ||
                    work.type != RecordWorkType.FOLLOW_UP || work.visitKey != b.visitKey)) return conflict
            if (children.size > 1) return conflict
            val child = children.singleOrNull()
            if (child != null) {
                if (work != null || child.submissionId == source.submissionId ||
                    child.formId != source.formId || child.formVersionId != b.targetFormVersionId) return conflict
                return ProtectedReconciliationState.LINKED_RECORD_VISIBLE to child
            }
            return if (work == null) unknown else ProtectedReconciliationState.READY_WITHOUT_VISIBLE_RECORD to null
        }
    }

    private fun checkAccount(projectId: String, b: QuarantinedWorkDraft) {
        val values = settings.getUnprotectedSettings(projectId)
        val paths = RecordWorkApiPaths.fromProjectUrl(values.getString(ProjectKeys.KEY_SERVER_URL).orEmpty())
        val user = values.getString(ProjectKeys.KEY_USERNAME).orEmpty()
        require(b.projectId == projectId && b.workType == RecordWorkType.FOLLOW_UP &&
            b.validationTransfers == 0 && !b.requiresDeprecatedId && !b.visitKey.isNullOrBlank() &&
            user.isNotBlank() && !values.getString(ProjectKeys.KEY_PASSWORD).isNullOrBlank() &&
            QuarantinedWorkDraftVault.sha256(projectId + "\n" + paths.records() + "\n" + user) ==
                b.actorContextSha256 &&
            QuarantinedWorkDraftVault(root).read(projectId, b.workId, b.actorContextSha256) == b)
    }

    private fun reconcile(projectId: String, b: QuarantinedWorkDraft): RemoteRecord {
        val remote = RecordWorkReadOnlyClient(settings)
        val snapshot = remote.load(projectId)
        val source = snapshot.records.single { it.submissionId == b.sourceSubmissionId }
        val child = snapshot.records.single { it.instanceId == b.reservedInstanceId }
        require(source.instanceId == b.sourceInstanceId &&
            child.submissionId != source.submissionId && child.formId == source.formId &&
            child.formVersionId == b.targetFormVersionId &&
            snapshot.pendingWork.none { it.id == b.workId } &&
            remote.completedVisit(projectId, b, child))
        return child
    }

    private fun attempt(area: File, b: QuarantinedWorkDraft): ProtectedSubmittedReceipt {
        val bytes = checkedBytes(File(area, "submission-attempt.aesgcm"), 4096)
        val clear = cipher.open(bytes, aad(b, "DC_SUBMISSION_ATTEMPT_V1"))
        try {
            val values = String(clear, Charsets.UTF_8).split("\n")
            require(values.size == 5 && UUID.fromString(values[0]).toString() == values[0] &&
                values[1].matches(digestPattern) && values[2].matches(digestPattern) &&
                values[3] == b.reservedInstanceId && values[4] == b.targetFormVersionId)
            val envelope = checkedBytes(File(area, "finalized-" + values[0] + ".aesgcm"),
                13 * 1024 * 1024)
            require(QuarantinedPrivateImageStore.digest(envelope) == values[2])
            return ProtectedSubmittedReceipt(values[0], values[1], values[2],
                QuarantinedPrivateImageStore.digest(bytes), values[3], "", values[4])
        } finally { clear.fill(0) }
    }

    private fun readReceipt(file: File, b: QuarantinedWorkDraft): ProtectedSubmittedReceipt {
        val clear = cipher.open(checkedBytes(file, 4096), aad(b, "DC_SUBMITTED_RECEIPT_V1"))
        try {
            val v = String(clear, Charsets.UTF_8).split("\n")
            require(v.size == 7 && UUID.fromString(v[0]).toString() == v[0] &&
                v.subList(1, 4).all { it.matches(digestPattern) } &&
                v[4] == b.reservedInstanceId && UUID.fromString(v[5]).toString() == v[5] &&
                v[5] != b.sourceSubmissionId && v[6] == b.targetFormVersionId)
            return ProtectedSubmittedReceipt(v[0], v[1], v[2], v[3], v[4], v[5], v[6])
        } finally { clear.fill(0) }
    }

    private fun checkedBytes(file: File, max: Int): ByteArray {
        require(file.isFile && file.canonicalFile == file.absoluteFile &&
            !Files.isSymbolicLink(file.toPath()) && file.length() in 30..max.toLong())
        return file.readBytes()
    }

    private fun encode(r: ProtectedSubmittedReceipt) = listOf(r.generation, r.packageSha256,
        r.ciphertextSha256, r.attemptSha256, r.instanceId, r.submissionId,
        r.targetFormVersionId).joinToString("\n").toByteArray(Charsets.UTF_8)

    private fun aad(b: QuarantinedWorkDraft, domain: String) =
        (if (domain == "DC_SUBMISSION_ATTEMPT_V1") listOf(domain, b.projectId,
            b.actorContextSha256, b.workId) else listOf(domain, b.projectId,
            b.actorContextSha256, b.workId, b.sourceSubmissionId, b.sourceInstanceId,
            b.targetFormVersionId, b.targetXFormSha256, b.reservedInstanceId,
            b.visitKey.orEmpty())).joinToString("\n").toByteArray(Charsets.UTF_8)
}
