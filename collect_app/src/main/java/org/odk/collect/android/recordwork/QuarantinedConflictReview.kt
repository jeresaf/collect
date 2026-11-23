package org.odk.collect.android.recordwork

/**
 * A MANUAL recovery index, not a draft editor or a source-data reader.
 *
 * A caller must first read the local work binding for the current project and
 * actor from QuarantinedWorkDraftVault. No XML, answer, media, key or credential
 * is read by this class. These opaque IDs are the only metadata the operator
 * may use to compare a quarantined work item with freshly fetched server
 * records. A new work item must be created separately after explicit review.
 */
data class QuarantinedConflictReview(
    val workId: String,
    val sourceSubmissionId: String,
    val targetFormVersionId: String,
    val targetVersion: String,
    val reservedInstanceId: String,
    val workType: RecordWorkType,
    val canReadDraftAnswers: Boolean = false,
    val canReadHistoricalAnswers: Boolean = false,
    val canCopyIntoNewWork: Boolean = false,
    val canRetarget: Boolean = false,
    val canFinalize: Boolean = false,
    val canUpload: Boolean = false
) {
    init {
        require(!canReadDraftAnswers && !canReadHistoricalAnswers &&
            !canCopyIntoNewWork && !canRetarget && !canFinalize && !canUpload) {
            "Conflict review is metadata only; never grant answer or submit rights"
        }
    }

    companion object {
        fun describe(binding: QuarantinedWorkDraft): QuarantinedConflictReview {
            require(binding.state == QuarantinedWorkDraft.STATE_QUARANTINED &&
                !binding.canOpenEditor && !binding.canFinalize && !binding.canUpload &&
                binding.workId.isNotBlank() && binding.sourceSubmissionId.isNotBlank() &&
                binding.reservedInstanceId.isNotBlank()) {
                "Only a protected, inert, actor-scoped work binding can be reviewed"
            }
            return QuarantinedConflictReview(
                binding.workId,
                binding.sourceSubmissionId,
                binding.targetFormVersionId,
                binding.targetVersion,
                binding.reservedInstanceId,
                binding.workType
            )
        }
    }
}
