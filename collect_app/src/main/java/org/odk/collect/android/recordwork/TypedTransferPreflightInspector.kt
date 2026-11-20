package org.odk.collect.android.recordwork

/**
 * M4C-2B-3C-3B-3A: authenticated, answer-free preflight only.
 *
 * This class deliberately cannot receive source XML or historical answers.
 * It never transforms, previews, returns or persists answer values. The
 * operator's encrypted request for review does not grant any transfer right.
 */
data class TypedTransferPreflight(
    val workId: String,
    val sourceXmlSha256: String,
    val sourceXFormSha256: String,
    val targetXFormSha256: String,
    val candidatePaths: List<String>,
    val pathsToReenter: Int,
    val pathsLeftEmpty: Int,
    val pathsRequestingSeparateReview: Int,
    val canReadHistoricalAnswers: Boolean = false,
    val canTransfer: Boolean = false,
    val canFinalize: Boolean = false,
    val canUpload: Boolean = false
) {
    init {
        require(!canReadHistoricalAnswers && !canTransfer && !canFinalize && !canUpload) {
            "A typed transfer preflight cannot grant historical source or submission permission"
        }
    }
}

/**
 * Reads cryptographically authenticated, immutable review decisions using
 * the existing AES-GCM store, then checks them against the *current* exact
 * native findings and bound work/schema.
 *
 * Caller must independently refetch READY owner-authorized work and fresh
 * source/target JavaRosa findings before invoking. This is NOT a cached
 * offline authorization and will never copy even a seemingly compatible value.
 */
class TypedTransferPreflightInspector(private val store: ProtectedTypedReviewStore) {
    fun inspect(
        binding: QuarantinedWorkDraft,
        plan: HistoricalMappingPlan,
        findings: List<TypedMappingFinding>
    ): TypedTransferPreflight {
        require(binding.workType == RecordWorkType.AMENDMENT &&
            binding.visitKey == null && binding.requiresDeprecatedId &&
            binding.state == QuarantinedWorkDraft.STATE_QUARANTINED &&
            !binding.canOpenEditor && !binding.canFinalize && !binding.canUpload &&
            binding.sourceXFormSha256 != null &&
            plan.workType == RecordWorkType.AMENDMENT &&
            plan.workId == binding.workId &&
            plan.sourceSubmissionId == binding.sourceSubmissionId &&
            plan.targetFormVersionId == binding.targetFormVersionId &&
            plan.sourceXFormSha256 == binding.sourceXFormSha256 &&
            plan.targetXFormSha256 == binding.targetXFormSha256 &&
            plan.candidates.map { it.targetPath } == findings.map { it.targetPath } &&
            findings.isNotEmpty() && findings.size <= 30) {
            "Work or native schemas no longer match"
        }

        val approval = requireNotNull(store.read(binding, plan, findings)) {
            "No authenticated typed operator review exists"
        }
        require(approval.workId == binding.workId &&
            approval.sourceXmlSha256 == binding.sourceXmlSha256 &&
            approval.sourceXFormSha256 == binding.sourceXFormSha256 &&
            approval.targetXFormSha256 == binding.targetXFormSha256 &&
            approval.decisions.keys == findings.map { it.targetPath }.toSet() &&
            !approval.canTransfer && !approval.canFinalize && !approval.canUpload) {
            "Typed operator review differs from the exact READY work"
        }

        // A REVIEW_SAME_TYPE is only a suggested later rule review. Even a
        // requested rule has no ability to read a historical answer.
        val requested = findings.filter {
            approval.decisions[it.targetPath] ==
                TypedReviewDecision.REQUEST_SEPARATE_RULE_REVIEW
        }
        require(requested.all { it.verdict == TypedMappingVerdict.REVIEW_SAME_TYPE }) {
            "Blocked typed fields cannot request historical mapping"
        }
        return TypedTransferPreflight(
            workId = binding.workId,
            sourceXmlSha256 = binding.sourceXmlSha256,
            sourceXFormSha256 = requireNotNull(binding.sourceXFormSha256),
            targetXFormSha256 = binding.targetXFormSha256,
            candidatePaths = requested.map { it.targetPath },
            pathsToReenter = approval.decisions.values.count {
                it == TypedReviewDecision.REENTER
            },
            pathsLeftEmpty = approval.decisions.values.count {
                it == TypedReviewDecision.LEAVE_EMPTY
            },
            pathsRequestingSeparateReview = requested.size
        )
    }
}
