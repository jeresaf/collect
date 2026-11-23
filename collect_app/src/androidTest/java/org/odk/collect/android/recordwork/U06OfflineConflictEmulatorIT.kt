package org.odk.collect.android.recordwork

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Runs on an actual Android 35 emulator with Android Keystore and no-backup
 * filesystem. This is an ON-DEVICE security/contract seam test with synthetic
 * server responses. It is NOT a live Spring Boot connection or ODK upload.
 */
@RunWith(AndroidJUnit4::class)
class U06OfflineConflictEmulatorIT {
    private val root: File = File(
        InstrumentationRegistry.getInstrumentation().targetContext.noBackupFilesDir,
        "u06-emulator-" + UUID.randomUUID()
    )
    private val project = "u06-emulator"
    private val actor = "a".repeat(64)
    private val source = "a8c07f51-b4c1-40a4-b31c-956d2ca5e048"
    private val updatedSource = "e41d8e2c-7d48-4ed9-8ea3-9bf5cd191e1c"
    private val workId = "e6bddba9-e98b-49cb-8bed-dce91d400520"
    private val targetVersion = "5a6f0bf6-795c-4d91-9861-16910542b4f1"
    private val sourceInstance = "uuid:9e1e9e90-5cc8-4e65-a5d0-acb5bdc1500d"
    private val field = "/data/name"
    private val privateAnswer = "ENCRYPTED_ONLY_EMULATOR_HOUSEHOLD_ANSWER"
    private val originalSourceHash = "1".repeat(64)
    private val historicalSchemaHash = "2".repeat(64)
    private val targetSchemaHash = "3".repeat(64)

    @After
    fun cleanTestFiles() {
        root.deleteRecursively()
        // The test intentionally does NOT export or delete the app's Keystore
        // key; the production implementation retains a non-exportable alias.
    }

    @Test
    fun staleAmendmentPreservesRealKeystoreCiphertextAndForbidsRetarget() {
        assertTrue(root.mkdirs())
        val vault = QuarantinedWorkDraftVault(root)
        val binding = stageSyntheticAmendment(vault)
        val cipher = AndroidKeyStorePrivateDraftCipher()
        val saved = EncryptedPrivateTextDraftStore(root, cipher, vault)
        saved.save(binding, mapOf(field to privateAnswer), listOf(field))

        val encryptedFile = File(root,
            "recordwork-drafts/$project/$workId/private-answers.aesgcm")
        assertTrue(encryptedFile.isFile)
        val priorBytes = encryptedFile.readBytes()
        assertTrue(priorBytes.size > 30)
        assertFalse(priorBytes.toString(Charsets.ISO_8859_1).contains(privateAnswer))

        // Synthetic 409 models the exact backend AMENDMENT preflight denial.
        // It is a policy outcome, not permission to decrypt for a new actor.
        val stale = ProtectedWorkRecovery.forFailure(
            PreparationException(PreparationError.CONFLICT)
        )
        assertEquals(ProtectedWorkFailure.STALE_OR_COMPLETED, stale.failure)
        assertTrue(stale.preserveSavedEncryptedDraft)
        assertTrue(stale.mayManuallyReviewServerRecords)
        assertFalse(stale.canOpenEditor)
        assertFalse(stale.canSave)
        assertFalse(stale.canFinalize)
        assertFalse(stale.canUpload)
        assertFalse(stale.mayAutomaticallyRetargetSource)
        assertFalse(stale.mayAutomaticallyRetrySubmission)

        val oldReview = QuarantinedConflictReview.describe(binding)
        assertEquals(source, oldReview.sourceSubmissionId)
        assertEquals(binding.reservedInstanceId, oldReview.reservedInstanceId)
        assertFalse(oldReview.canReadHistoricalAnswers)
        assertFalse(oldReview.canReadDraftAnswers)
        assertFalse(oldReview.canCopyIntoNewWork)
        assertFalse(oldReview.canUpload)

        // Match the JSON shape produced by backend U06-T02's real PostgreSQL
        // HTTP tests: archived old source=true, current replacement=false.
        val listing = """
            [
              {"submissionId":"$source","instanceId":"$sourceInstance",
               "formId":"$source","formVersionId":"$targetVersion",
               "submittedAt":"2026-11-23T09:00:00Z",
               "amendmentSuperseded":true},
              {"submissionId":"$updatedSource",
               "instanceId":"uuid:99f3d814-26a4-4f18-8b96-226c2e3b9b18",
               "formId":"$source","formVersionId":"$targetVersion",
               "submittedAt":"2026-11-23T10:00:00Z",
               "amendmentSuperseded":false}
            ]
        """.trimIndent()
        val records = RecordWorkJsonParser.records(listing)
        assertEquals(2, records.size)
        assertEquals(RemoteAmendmentRevision.SUPERSEDED, records[0].amendmentRevision)
        assertFalse(records[0].mayOfferAmendment)
        assertEquals(RemoteAmendmentRevision.CURRENT, records[1].amendmentRevision)
        assertTrue(records[1].mayOfferAmendment)
        assertNotEquals(binding.sourceSubmissionId, records[1].submissionId)

        // Navigating to a newly authorized server source cannot change the
        // reserved old instance, the signed local binding, or encrypted bytes.
        val restoredBinding = vault.read(project, workId, actor)
        assertEquals(binding, restoredBinding)
        assertArrayEquals(priorBytes, encryptedFile.readBytes())
        val reopened = EncryptedPrivateTextDraftStore(
            root, AndroidKeyStorePrivateDraftCipher(), vault
        ).read(binding, listOf(field))
        assertTrue(reopened.hasSavedDraft)
        assertEquals(privateAnswer, reopened.answers[field])
        assertFalse(reopened.canSubmit)
        assertFalse(reopened.canFinalize)

        val otherActor = "b".repeat(64)
        var refused = false
        try { vault.read(project, workId, otherActor) }
        catch (_: IllegalArgumentException) { refused = true }
        assertTrue("Cross-enumerator binding must not be readable", refused)
        assertArrayEquals(priorBytes, encryptedFile.readBytes())
    }

    @Test
    fun unknownSourceAndRevokedActorDoNotGainAmendmentAuthority() {
        val olderListing = """
            [{"submissionId":"$source","instanceId":"$sourceInstance",
              "formId":"$source","formVersionId":"$targetVersion",
              "submittedAt":"2026-11-23T09:00:00Z"}]
        """.trimIndent()
        val record = RecordWorkJsonParser.records(olderListing).single()
        assertEquals(RemoteAmendmentRevision.UNKNOWN, record.amendmentRevision)
        assertFalse(record.mayOfferAmendment)

        for (reason in listOf(PreparationError.ACCESS_DENIED,
            PreparationError.NETWORK, PreparationError.LOGIN_REQUIRED)) {
            val denied = ProtectedWorkRecovery.forFailure(PreparationException(reason))
            assertFalse(denied.mayManuallyReviewServerRecords)
            assertFalse(denied.canOpenEditor)
            assertFalse(denied.canSave)
            assertFalse(denied.canFinalize)
            assertFalse(denied.canUpload)
            assertFalse(denied.mayAutomaticallyRetrySubmission)
        }
    }

    private fun stageSyntheticAmendment(
        vault: QuarantinedWorkDraftVault
    ): QuarantinedWorkDraft {
        val plan = HistoricalMappingPlan(
            workId = workId, sourceSubmissionId = source,
            targetFormVersionId = targetVersion,
            workType = RecordWorkType.AMENDMENT,
            sourceXFormSha256 = historicalSchemaHash,
            targetXFormSha256 = targetSchemaHash,
            sourceFormId = "household",
            sourceVersion = "1.0", targetFormId = "household",
            targetVersion = "2.0",
            candidates = listOf(TargetFieldCandidate(
                field, null, setOf(MappingRisk.EXPLICIT_REVIEW_REQUIRED), false)),
            removedSourcePaths = emptySet(),
            globalRisks = setOf(MappingRisk.EXPLICIT_REVIEW_REQUIRED)
        )
        val prepared = PreparedRecordPreview(
            workId = workId, sourceSubmissionId = source,
            targetFormVersionId = targetVersion,
            targetFormId = "household", targetFormVersion = "2.0",
            type = RecordWorkType.AMENDMENT, mappingApprovalRequired = true,
            assessment = CrossVersionAssessment(
                workId, source, targetVersion, RecordWorkType.AMENDMENT,
                originalSourceHash, targetSchemaHash,
                "1.0", "2.0", 1, 1, 0, emptyList(),
                setOf(MappingIssue.EXPLICIT_OPERATOR_APPROVAL_REQUIRED)
            ),
            mappingPlan = plan,
            nativeStaging = NativeStagingEvidence(
                workId, source, targetVersion, RecordWorkType.AMENDMENT,
                historicalSchemaHash, targetSchemaHash,
                true, true, 0, false
            ),
            sourceInstanceId = sourceInstance,
            visitKey = null
        )
        val review = MappingReviewSnapshot(
            workId, historicalSchemaHash, targetSchemaHash,
            mapOf(field to MappingDecision.REENTER), true
        )
        val proof = ReviewedTransferEvidence(
            workId, source, targetVersion, RecordWorkType.AMENDMENT,
            sourceInstance, originalSourceHash,
            historicalSchemaHash, targetSchemaHash,
            pathsReviewed = 1, pathsTransferred = 0,
            pathsToReenter = 1, pathsLeftEmpty = 0
        )
        return vault.stage(project, actor, prepared, review, proof)
    }
}
