package org.odk.collect.android.recordwork

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** No production data. Tests durable inert work binding and refusal of unsafe edits. */
class QuarantinedWorkDraftVaultTest {
    @get:Rule val temporary = TemporaryFolder()

    private val project = "local-odk-project"
    private val actor = "a".repeat(64)
    private val work = "e6bddba9-e98b-49cb-8bed-dce91d400520"
    private val source = "a8c07f51-b4c1-40a4-b31c-956d2ca5e048"
    private val target = "5a6f0bf6-795c-4d91-9861-16910542b4f1"
    private val instanceId = "uuid:9e1e9e90-5cc8-4e65-a5d0-acb5bdc1500d"
    private val sourceSha = "1".repeat(64)
    private val sourceFormSha = "2".repeat(64)
    private val targetFormSha = "3".repeat(64)

    @Test fun amendmentIsIdempotentAndPreservesReservedNewIdentity() {
        val (preview, review, transfer) = amendment()
        val storage = QuarantinedWorkDraftVault(temporary.root)
        val first = storage.stage(project, actor, preview, review, transfer)
        val second = storage.stage(project, actor, preview, review, transfer)
        val recovered = QuarantinedWorkDraftVault(temporary.root).read(project, work, actor)
        assertEquals(first, second)
        assertEquals(first, recovered)
        assertTrue(first.reservedInstanceId.startsWith("uuid:"))
        assertTrue(first.reservedInstanceId != first.sourceInstanceId)
        assertTrue(first.requiresDeprecatedId)
        assertNull(first.visitKey)
        assertEquals(1, first.validationTransfers)
        assertFalse(first.canOpenEditor)
        assertFalse(first.canFinalize)
        assertFalse(first.canUpload)
    }

    @Test fun bindingIsScopedToProjectAndAccountFingerprint() {
        val (preview, review, transfer) = amendment()
        val storage = QuarantinedWorkDraftVault(temporary.root)
        storage.stage(project, actor, preview, review, transfer)
        assertNull(storage.read("other-project", work, actor))
        assertNull(storage.read(project, "aaaaaaab-aaaa-4aaa-aaaa-aaaaaaaaaaaa", actor))
        rejects { storage.read(project, work, "b".repeat(64)) }
        rejects { storage.stage("../outside", actor, preview, review, transfer) }
    }

    @Test fun noHouseholdAnswerDataOrXFormIsPersisted() {
        val (preview, review, transfer) = amendment()
        val storage = QuarantinedWorkDraftVault(temporary.root)
        storage.stage(project, actor, preview, review, transfer)
        val manifest = File(temporary.root,
            "recordwork-drafts/$project/$work/binding.properties")
        assertTrue(manifest.exists())
        val content = manifest.readText()
        assertFalse(content.contains("PRIVATE_HOUSEHOLD_ANSWER"))
        assertFalse(content.contains("<data"))
        assertFalse(content.contains("<h:html"))
        assertFalse(content.contains("Authorization"))
        assertEquals("binding.properties",
            requireNotNull(manifest.parentFile.listFiles()).single().name)
    }

    @Test fun changedSourceOrFormBlocksRepeatedStagingAndPreservesOriginal() {
        val (preview, review, transfer) = amendment()
        val storage = QuarantinedWorkDraftVault(temporary.root)
        val first = storage.stage(project, actor, preview, review, transfer)
        val changed = preview.copy(assessment =
            requireNotNull(preview.assessment).copy(sourceSha256 = "4".repeat(64)))
        rejects { storage.stage(project, actor, changed, review,
            requireNotNull(transfer).copy(sourceXmlSha256 = "4".repeat(64))) }
        assertEquals(first, storage.read(project, work, actor))
    }

    @Test fun corruptedManifestIsNotRecoveredAsTrustedData() {
        val (preview, review, transfer) = amendment()
        val storage = QuarantinedWorkDraftVault(temporary.root)
        storage.stage(project, actor, preview, review, transfer)
        val manifest = File(temporary.root,
            "recordwork-drafts/$project/$work/binding.properties")
        manifest.appendText("\nworkType=FOLLOW_UP\n")
        rejects { storage.read(project, work, actor) }
    }

    @Test fun missingNativeOrServerSourceIdentityForbidsStaging() {
        val (preview, review, transfer) = amendment()
        val storage = QuarantinedWorkDraftVault(temporary.root)
        rejects { storage.stage(project, actor, preview.copy(sourceInstanceId = null),
            review, transfer) }
        rejects { storage.stage(project, actor, preview.copy(nativeStaging = null),
            review, transfer) }
        rejects { storage.stage(project, actor, preview.copy(mappingApprovalRequired = false),
            review, transfer) }
        assertFalse(File(temporary.root, "recordwork-drafts/$project/$work").exists())
    }

    @Test fun amendmentRequiresMatchingReviewedTransferEvidence() {
        val (preview, review, transfer) = amendment()
        val storage = QuarantinedWorkDraftVault(temporary.root)
        rejects { storage.stage(project, actor, preview, review) }
        rejects { storage.stage(project, actor, preview, review,
            requireNotNull(transfer).copy(sourceInstanceId =
                "uuid:aaaaaaaa-aaaa-4aaa-aaaa-aaaaaaaaaaaa")) }
        rejects { storage.stage(project, actor, preview, review,
            requireNotNull(transfer).copy(pathsTransferred = 0)) }
    }

    @Test fun independentFollowupCreatesSeparateVisitWithoutDeprecatedIdOrCopy() {
        val (preview, review) = followUp()
        val result = QuarantinedWorkDraftVault(temporary.root)
            .stage(project, actor, preview, review)
        assertEquals(RecordWorkType.FOLLOW_UP, result.workType)
        assertEquals("household-visit-2", result.visitKey)
        assertEquals(0, result.validationTransfers)
        assertFalse(result.requiresDeprecatedId)
        assertTrue(result.reservedInstanceId != result.sourceInstanceId)
        assertFalse(result.canUpload)
    }

    @Test fun followupNeverAcceptsOldAnswerTransfer() {
        val (preview, review) = followUp()
        val storage = QuarantinedWorkDraftVault(temporary.root)
        rejects { storage.stage(project, actor, preview,
            review.copy(decisions = mapOf("/data/name" to MappingDecision.REQUEST_REVIEWED_TRANSFER))) }
        rejects { storage.stage(project, actor, preview.copy(visitKey = null), review) }
    }

    @Test fun quarantinedStateCannotBePromotedByModelCopy() {
        val (preview, review, transfer) = amendment()
        val value = QuarantinedWorkDraftVault(temporary.root)
            .stage(project, actor, preview, review, transfer)
        rejects { value.copy(canOpenEditor = true) }
        rejects { value.copy(canFinalize = true) }
        rejects { value.copy(canUpload = true) }
        rejects { value.copy(state = "READY") }
    }

    private fun amendment(): Triple<PreparedRecordPreview, MappingReviewSnapshot,
        ReviewedTransferEvidence> {
        val path = "/data/name"
        val plan = mappingPlan(RecordWorkType.AMENDMENT, sourceFormSha,
            true)
        val preview = preview(RecordWorkType.AMENDMENT, plan, null, true)
        val review = MappingReviewSnapshot(work, sourceFormSha, targetFormSha,
            mapOf(path to MappingDecision.REQUEST_REVIEWED_TRANSFER), true)
        val transfer = ReviewedTransferEvidence(
            work, source, target, RecordWorkType.AMENDMENT, instanceId,
            sourceSha, sourceFormSha, targetFormSha,
            1, 1, 0, 0
        )
        return Triple(preview, review, transfer)
    }

    private fun followUp(): Pair<PreparedRecordPreview, MappingReviewSnapshot> {
        val plan = mappingPlan(RecordWorkType.FOLLOW_UP, null, false)
        val preview = preview(RecordWorkType.FOLLOW_UP, plan,
            "household-visit-2", false)
        val review = MappingReviewSnapshot(work, null, targetFormSha,
            mapOf("/data/name" to MappingDecision.REENTER), true)
        return preview to review
    }

    private fun mappingPlan(type: RecordWorkType, sourceFingerprint: String?,
                            safeTransfer: Boolean) = HistoricalMappingPlan(
        workId = work,
        sourceSubmissionId = source,
        targetFormVersionId = target,
        workType = type,
        sourceXFormSha256 = sourceFingerprint,
        targetXFormSha256 = targetFormSha,
        sourceFormId = "household",
        sourceVersion = "1",
        targetFormId = "household",
        targetVersion = "2",
        candidates = listOf(TargetFieldCandidate("/data/name",
            if (safeTransfer) "/data/name" else null,
            if (safeTransfer) emptySet() else setOf(MappingRisk.DIFFERENT_FORM_FOLLOW_UP),
            safeTransfer)),
        removedSourcePaths = emptySet(),
        globalRisks = setOf(MappingRisk.EXPLICIT_REVIEW_REQUIRED)
    )

    private fun preview(type: RecordWorkType, plan: HistoricalMappingPlan,
                        visit: String?, historical: Boolean): PreparedRecordPreview =
        PreparedRecordPreview(
            workId = work,
            sourceSubmissionId = source,
            targetFormVersionId = target,
            targetFormId = "household",
            targetFormVersion = "2",
            type = type,
            mappingApprovalRequired = true,
            assessment = CrossVersionAssessment(
                work, source, target, type, sourceSha, targetFormSha,
                "1", "2", 1, 1, 1,
                emptyList(), setOf(MappingIssue.JAVAROSA_EVALUATION_REQUIRED)
            ),
            mappingPlan = plan,
            nativeStaging = NativeStagingEvidence(
                work, source, target, type, plan.sourceXFormSha256,
                targetFormSha, true, historical, 1, true
            ),
            sourceInstanceId = instanceId,
            visitKey = visit
        )

    private fun rejects(block: () -> Unit) {
        var rejected = false
        try { block() } catch (_: Exception) { rejected = true }
        assertTrue("Unsafe quarantined work must be rejected", rejected)
    }
}
