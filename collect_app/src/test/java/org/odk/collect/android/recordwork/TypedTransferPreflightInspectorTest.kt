package org.odk.collect.android.recordwork

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Answer-free synthetic work and schema evidence. No source XML is used. */
class TypedTransferPreflightInspectorTest {
    @get:Rule val disk = TemporaryFolder()
    private val project = "synthetic-project"
    private val actor = "a".repeat(64)
    private val work = "e6bddba9-e98b-49cb-8bed-dce91d400520"
    private val source = "a8c07f51-b4c1-40a4-b31c-956d2ca5e048"
    private val target = "5a6f0bf6-795c-4d91-9861-16910542b4f1"
    private val instance = "uuid:9e1e9e90-5cc8-4e65-a5d0-acb5bdc1500d"
    private val sourceHash = "3".repeat(64)
    private val oldHash = "1".repeat(64)
    private val newHash = "2".repeat(64)
    private val path = "/data/age"
    private val cipher = EncryptedPrivateTextDraftStoreTest.TestAesGcmCipher()

    private data class Context(
        val binding: QuarantinedWorkDraft,
        val plan: HistoricalMappingPlan,
        val findings: List<TypedMappingFinding>
    )

    private fun context(): Context {
        val plan = HistoricalMappingPlan(
            work, source, target, RecordWorkType.AMENDMENT, oldHash, newHash,
            "household", "1", "household", "2",
            listOf(TargetFieldCandidate(path, path, emptySet(), true)),
            emptySet(), emptySet()
        )
        val review = MappingReviewSnapshot(work, oldHash, newHash,
            mapOf(path to MappingDecision.REENTER), true)
        val prepared = PreparedRecordPreview(
            workId = work, sourceSubmissionId = source,
            targetFormVersionId = target, targetFormId = "household",
            targetFormVersion = "2", type = RecordWorkType.AMENDMENT,
            mappingApprovalRequired = true,
            assessment = CrossVersionAssessment(
                work, source, target, RecordWorkType.AMENDMENT,
                sourceHash, newHash, "1", "2", 1, 1, 0,
                emptyList(), setOf(MappingIssue.JAVAROSA_EVALUATION_REQUIRED)
            ),
            mappingPlan = plan,
            nativeStaging = NativeStagingEvidence(
                work, source, target, RecordWorkType.AMENDMENT,
                oldHash, newHash, true, true, 1, true
            ),
            sourceInstanceId = instance,
            visitKey = null
        )
        val evidence = ReviewedTransferEvidence(
            work, source, target, RecordWorkType.AMENDMENT,
            instance, sourceHash, oldHash, newHash, 1, 0, 1, 0
        )
        val binding = QuarantinedWorkDraftVault(disk.root)
            .stage(project, actor, prepared, review, evidence)
        return Context(binding, plan, listOf(
            TypedMappingFinding(path, PrivateScalarType.INTEGER,
                TypedMappingVerdict.REVIEW_SAME_TYPE, "Native same-type candidate")
        ))
    }

    private fun store() = ProtectedTypedReviewStore(disk.root, cipher)
    private fun inspector() = TypedTransferPreflightInspector(store())

    @Test fun verifiedReviewYieldsOnlyInertCandidatePaths() {
        val c = context()
        store().save(c.binding, c.plan, c.findings,
            mapOf(path to TypedReviewDecision.REQUEST_SEPARATE_RULE_REVIEW))
        val result = inspector().inspect(c.binding, c.plan, c.findings)
        assertEquals(listOf(path), result.candidatePaths)
        assertEquals(1, result.pathsRequestingSeparateReview)
        assertFalse(result.canReadHistoricalAnswers)
        assertFalse(result.canTransfer)
        assertFalse(result.canFinalize)
        assertFalse(result.canUpload)
    }

    @Test fun noApprovedManifestIsNotPermission() {
        val c = context()
        rejects { inspector().inspect(c.binding, c.plan, c.findings) }
    }

    @Test fun explicitReenterDoesNotProduceTransferCandidate() {
        val c = context()
        store().save(c.binding, c.plan, c.findings,
            mapOf(path to TypedReviewDecision.REENTER))
        val result = inspector().inspect(c.binding, c.plan, c.findings)
        assertEquals(emptyList<String>(), result.candidatePaths)
        assertEquals(1, result.pathsToReenter)
    }

    @Test fun driftedActorOrSourceHashCannotReopenReview() {
        val c = context()
        store().save(c.binding, c.plan, c.findings,
            mapOf(path to TypedReviewDecision.REQUEST_SEPARATE_RULE_REVIEW))
        rejects { inspector().inspect(
            c.binding.copy(actorContextSha256 = "b".repeat(64)), c.plan, c.findings) }
        rejects { inspector().inspect(
            c.binding.copy(sourceXmlSha256 = "f".repeat(64)), c.plan, c.findings) }
        rejects { inspector().inspect(c.binding, c.plan.copy(
            targetXFormSha256 = "f".repeat(64)), c.findings) }
    }

    @Test fun changedTypeCannotBeReinterpretedAfterReview() {
        val c = context()
        store().save(c.binding, c.plan, c.findings,
            mapOf(path to TypedReviewDecision.REQUEST_SEPARATE_RULE_REVIEW))
        rejects { inspector().inspect(c.binding, c.plan,
            c.findings.map { it.copy(nativeType = PrivateScalarType.DECIMAL) }) }
    }

    @Test fun evenAConstructedPreflightCannotAuthorizeUpload() {
        val c = context()
        store().save(c.binding, c.plan, c.findings,
            mapOf(path to TypedReviewDecision.REQUEST_SEPARATE_RULE_REVIEW))
        val result = inspector().inspect(c.binding, c.plan, c.findings)
        rejects { result.copy(canReadHistoricalAnswers = true) }
        rejects { result.copy(canTransfer = true) }
        rejects { result.copy(canFinalize = true) }
        rejects { result.copy(canUpload = true) }
    }

    private fun rejects(action: () -> Unit) {
        var failed = false
        try { action() } catch (_: Exception) { failed = true }
        assertTrue("Unsafe historical-transfer preflight must fail closed", failed)
    }
}
