package org.odk.collect.android.recordwork

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ProtectedTypedReviewStoreTest {
    @get:Rule val disk = TemporaryFolder()
    private val project = "synthetic-project"
    private val actor = "a".repeat(64)
    private val work = "e6bddba9-e98b-49cb-8bed-dce91d400520"
    private val source = "a8c07f51-b4c1-40a4-b31c-956d2ca5e048"
    private val target = "5a6f0bf6-795c-4d91-9861-16910542b4f1"
    private val instance = "uuid:9e1e9e90-5cc8-4e65-a5d0-acb5bdc1500d"
    private val oldHash = "1".repeat(64)
    private val newHash = "2".repeat(64)
    private val sourceHash = "3".repeat(64)
    private val path = "/data/age"
    private val cipher = EncryptedPrivateTextDraftStoreTest.TestAesGcmCipher()

    private data class Fixture(
        val binding: QuarantinedWorkDraft,
        val plan: HistoricalMappingPlan,
        val findings: List<TypedMappingFinding>
    )

    private fun fixture(): Fixture {
        val plan = HistoricalMappingPlan(
            workId = work, sourceSubmissionId = source, targetFormVersionId = target,
            workType = RecordWorkType.AMENDMENT, sourceXFormSha256 = oldHash,
            targetXFormSha256 = newHash,
            sourceFormId = "household", sourceVersion = "1",
            targetFormId = "household", targetVersion = "2",
            candidates = listOf(TargetFieldCandidate(path, path, emptySet(), true)),
            removedSourcePaths = emptySet(), globalRisks = emptySet()
        )
        val review = MappingReviewSnapshot(
            work, oldHash, newHash, mapOf(path to MappingDecision.REENTER), true
        )
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
            sourceInstanceId = instance, visitKey = null
        )
        val proof = ReviewedTransferEvidence(
            work, source, target, RecordWorkType.AMENDMENT,
            instance, sourceHash, oldHash, newHash,
            1, 0, 1, 0
        )
        val binding = QuarantinedWorkDraftVault(disk.root)
            .stage(project, actor, prepared, review, proof)
        return Fixture(binding, plan, listOf(
            TypedMappingFinding(path, PrivateScalarType.INTEGER,
                TypedMappingVerdict.REVIEW_SAME_TYPE, "Native same-type candidate")
        ))
    }

    private fun store() = ProtectedTypedReviewStore(disk.root, cipher)
    private fun file() = File(disk.root,
        "recordwork-drafts/$project/$work/typed-mapping-review.aesgcm")
    private val accepted = mapOf(path to TypedReviewDecision.REQUEST_SEPARATE_RULE_REVIEW)

    @Test fun encryptedReviewSurvivesRestartWithoutAnswerValuesOrPermission() {
        val f = fixture()
        val initial = store().save(f.binding, f.plan, f.findings, accepted)
        val reopened = store().read(f.binding, f.plan, f.findings)
        assertEquals(initial, reopened)
        assertEquals(accepted, reopened?.decisions)
        assertFalse(initial.canTransfer)
        assertFalse(initial.canFinalize)
        assertFalse(initial.canUpload)
        assertTrue(file().isFile)
        assertFalse(file().readBytes().toString(Charsets.ISO_8859_1)
            .contains("Native same-type candidate"))
        assertTrue(file().parentFile.listFiles().orEmpty().none { it.name.endsWith(".xml") })
    }

    @Test fun immutableDecisionsCannotChangeAfterFirstApproval() {
        val f = fixture()
        val initial = store().save(f.binding, f.plan, f.findings, accepted)
        assertEquals(initial, store().save(f.binding, f.plan, f.findings, accepted))
        rejects {
            store().save(f.binding, f.plan, f.findings,
                mapOf(path to TypedReviewDecision.LEAVE_EMPTY))
        }
        assertEquals(initial, store().read(f.binding, f.plan, f.findings))
    }

    @Test fun blockedFieldCannotRequestMappingRule() {
        val f = fixture()
        val denied = f.findings.map {
            it.copy(verdict = TypedMappingVerdict.BLOCKED_TYPE_CHANGE)
        }
        rejects { store().save(f.binding, f.plan, denied, accepted) }
        assertFalse(file().exists())
    }

    @Test fun changedNativeInventoryOrActorFailsClosed() {
        val f = fixture()
        store().save(f.binding, f.plan, f.findings, accepted)
        rejects {
            store().read(f.binding, f.plan, f.findings.map {
                it.copy(nativeType = PrivateScalarType.DECIMAL)
            })
        }
        rejects {
            store().read(f.binding.copy(actorContextSha256 = "b".repeat(64)),
                f.plan, f.findings)
        }
        rejects {
            store().read(f.binding.copy(sourceXmlSha256 = "f".repeat(64)),
                f.plan, f.findings)
        }
        assertEquals(accepted, store().read(f.binding, f.plan, f.findings)?.decisions)
    }

    @Test fun tamperedCiphertextIsRejected() {
        val f = fixture()
        store().save(f.binding, f.plan, f.findings, accepted)
        val bytes = file().readBytes()
        bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
        file().writeBytes(bytes)
        rejects { store().read(f.binding, f.plan, f.findings) }
    }

    @Test fun neverGrantsUploadOrHistoricalTransfer() {
        val f = fixture()
        val saved = store().save(f.binding, f.plan, f.findings,
            mapOf(path to TypedReviewDecision.REENTER))
        rejects { saved.copy(canTransfer = true) }
        rejects { saved.copy(canFinalize = true) }
        rejects { saved.copy(canUpload = true) }
    }

    private fun rejects(block: () -> Unit) {
        var rejected = false
        try { block() } catch (_: Exception) { rejected = true }
        assertTrue("Unsafe mapping review operation must fail closed", rejected)
    }
}
