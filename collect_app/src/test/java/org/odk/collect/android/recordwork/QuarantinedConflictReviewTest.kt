package org.odk.collect.android.recordwork

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Uses synthetic opaque IDs; no Android key, sensitive XML, network or
 * transfer fixture. Review cannot expose protected answer values.
 */
class QuarantinedConflictReviewTest {
    private val workId = "e6bddba9-e98b-49cb-8bed-dce91d400520"
    private val sourceId = "a8c07f51-b4c1-40a4-b31c-956d2ca5e048"
    private val targetId = "5a6f0bf6-795c-4d91-9861-16910542b4f1"
    private val reserved = "uuid:b2edb2a9-3250-479a-a30a-0cdb1586b055"

    private fun binding(type: RecordWorkType = RecordWorkType.AMENDMENT) =
        QuarantinedWorkDraft(
            projectId = "local-project",
            actorContextSha256 = "a".repeat(64),
            workId = workId,
            sourceSubmissionId = sourceId,
            sourceInstanceId = "uuid:9e1e9e90-5cc8-4e65-a5d0-acb5bdc1500d",
            targetFormVersionId = targetId,
            targetFormId = "household",
            targetVersion = "2.0",
            workType = type,
            visitKey = if (type == RecordWorkType.FOLLOW_UP)
                "visit-2" else null,
            reservedInstanceId = reserved,
            sourceXmlSha256 = "b".repeat(64),
            sourceXFormSha256 = "c".repeat(64),
            targetXFormSha256 = "d".repeat(64),
            reviewedPaths = 2,
            validationTransfers = 0,
            requiresDeprecatedId = type == RecordWorkType.AMENDMENT
        )

    @Test fun staleAmendmentShowsIdentifiersOnlyAndNeverAnswers() {
        val draft = binding()
        val review = QuarantinedConflictReview.describe(draft)
        assertEquals(workId, review.workId)
        assertEquals(sourceId, review.sourceSubmissionId)
        assertEquals(targetId, review.targetFormVersionId)
        assertEquals(reserved, review.reservedInstanceId)
        assertEquals("2.0", review.targetVersion)
        assertEquals(RecordWorkType.AMENDMENT, review.workType)
        assertFalse(review.toString().contains(draft.sourceXmlSha256))
        assertFalse(review.toString().contains(draft.actorContextSha256))
        assertFalse(review.canReadDraftAnswers)
        assertFalse(review.canReadHistoricalAnswers)
        assertFalse(review.canCopyIntoNewWork)
        assertFalse(review.canRetarget)
        assertFalse(review.canFinalize)
        assertFalse(review.canUpload)
    }

    @Test fun completedFollowupAlsoRemainsInertAndNeverChangesOldInstance() {
        val review = QuarantinedConflictReview.describe(binding(RecordWorkType.FOLLOW_UP))
        assertEquals(RecordWorkType.FOLLOW_UP, review.workType)
        assertEquals(reserved, review.reservedInstanceId)
        assertFalse(review.canCopyIntoNewWork)
        assertFalse(review.canUpload)
    }

    @Test fun rejectedPermissionsCannotBeForgedByCopy() {
        val review = QuarantinedConflictReview.describe(binding())
        for (forged in listOf(
            { review.copy(canReadDraftAnswers = true) },
            { review.copy(canReadHistoricalAnswers = true) },
            { review.copy(canCopyIntoNewWork = true) },
            { review.copy(canRetarget = true) },
            { review.copy(canFinalize = true) },
            { review.copy(canUpload = true) }
        )) {
            var rejected = false
            try { forged() } catch (_: IllegalArgumentException) {
                rejected = true
            }
            assertTrue("Reconciliation metadata must not grant permissions", rejected)
        }
    }

    @Test fun manualBrowserReviewIsOnlyVisibleAfterConflictNotRevocationOrOutage() {
        val stale = ProtectedWorkRecovery.forFailure(
            PreparationException(PreparationError.CONFLICT)
        )
        assertTrue(stale.mayManuallyReviewServerRecords)
        assertFalse(stale.canReadDraftAnswersForReview())
        assertFalse(stale.mayAutomaticallyRetargetSource)
        assertFalse(stale.mayAutomaticallyRetrySubmission)
        for (reason in listOf(
            PreparationError.NETWORK,
            PreparationError.ACCESS_DENIED,
            PreparationError.NOT_FOUND,
            PreparationError.LOGIN_REQUIRED,
            PreparationError.SERVER_ERROR
        )) {
            val other = ProtectedWorkRecovery.forFailure(PreparationException(reason))
            assertFalse(other.mayManuallyReviewServerRecords)
        }
    }

    private fun ProtectedWorkRecovery.canReadDraftAnswersForReview() =
        canOpenEditor || canSave || canUpload
}
