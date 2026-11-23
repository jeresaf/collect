package org.odk.collect.android.recordwork

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** JCE AES-GCM test seam; production uses non-exportable Android Keystore key. */
class EncryptedPrivateTextDraftStoreTest {
    @get:Rule val disk = TemporaryFolder()
    private val project = "local-odk-project"
    private val user = "a".repeat(64)
    private val work = "e6bddba9-e98b-49cb-8bed-dce91d400520"
    private val source = "a8c07f51-b4c1-40a4-b31c-956d2ca5e048"
    private val target = "5a6f0bf6-795c-4d91-9861-16910542b4f1"
    private val paths = listOf("/data/name", "/data/extra")
    private val privateAnswer = "SENSITIVE_HOUSEHOLD_ANSWER_NOT_PLAINTEXT"
    private val jce = TestAesGcmCipher()

    @Test fun encryptedSaveAndReopenPreservesValuesAndNoPlaintextLeaks() {
        val binding = stage()
        val repository = store()
        val answers = mapOf(paths[0] to privateAnswer, paths[1] to "Second visit")
        repository.save(binding, answers, paths)
        val afterRestart = store().read(binding, paths)
        assertTrue(afterRestart.hasSavedDraft)
        assertEquals(answers, afterRestart.answers)
        assertFalse(afterRestart.canFinalize)
        assertFalse(afterRestart.canSubmit)
        val file = encryptedFile()
        assertTrue(file.isFile)
        assertFalse(file.readBytes().toString(Charsets.ISO_8859_1).contains(privateAnswer))
        assertEquals("private-answers.aesgcm", file.name)
        assertFalse(file.parentFile.listFiles().orEmpty().any { it.name.endsWith(".xml") })
    }

    @Test fun staleAndOfflineRecoveryPreserveExactEncryptedDraftBytes() {
        val binding = stage()
        val expected = mapOf(paths[0] to privateAnswer, paths[1] to "Private followup")
        store().save(binding, expected, paths)
        val before = encryptedFile().readBytes()

        for (failure in listOf(PreparationError.CONFLICT, PreparationError.NETWORK,
            PreparationError.ACCESS_DENIED)) {
            val recovery = ProtectedWorkRecovery.forFailure(
                PreparationException(failure)
            )
            assertTrue(recovery.preserveSavedEncryptedDraft)
            assertFalse(recovery.canOpenEditor)
            assertFalse(recovery.canSave)
            assertFalse(recovery.canFinalize)
            assertFalse(recovery.canUpload)
            assertFalse(recovery.mayAutomaticallyRetargetSource)
            assertFalse(recovery.mayAutomaticallyRetrySubmission)
            assertTrue(before.contentEquals(encryptedFile().readBytes()))
            val recovered = store().read(binding, paths)
            assertEquals(expected, recovered.answers)
            assertTrue(recovered.hasSavedDraft)
            assertFalse(recovered.canSubmit)
            // This test has no reauthorization: a storage read alone MUST NOT
            // constitute permission to show or send a quarantined draft.
        }
        rejects { store().read(binding.copy(actorContextSha256 = "b".repeat(64)), paths) }
        assertFalse(encryptedFile().readBytes().toString(Charsets.ISO_8859_1)
            .contains(privateAnswer))
    }

    @Test fun zeroValuesAreNotMistakenForAValidSubmittedInstance() {
        val binding = stage()
        assertFalse(store().read(binding, paths).hasSavedDraft)
        store().save(binding, paths.associateWith { "" }, paths)
        val saved = store().read(binding, paths)
        assertTrue(saved.hasSavedDraft)
        assertEquals("", saved.answers[paths[0]])
        assertFalse(saved.canSubmit)
        rejects { saved.copy(canSubmit = true) }
        rejects { saved.copy(canFinalize = true) }
    }

    @Test fun tamperedCiphertextIsRejectedAndNotRendered() {
        val binding = stage()
        store().save(binding, paths.associateWith { privateAnswer }, paths)
        val file = encryptedFile()
        val changed = file.readBytes()
        changed[changed.size - 1] = (changed.last().toInt() xor 1).toByte()
        file.writeBytes(changed)
        rejects { store().read(binding, paths) }
    }

    @Test fun wrongActorAndProjectCannotReopenAProtectedDraft() {
        val binding = stage()
        store().save(binding, paths.associateWith { "value" }, paths)
        rejects { store().read(binding.copy(actorContextSha256 = "b".repeat(64)), paths) }
        rejects { store().read(binding.copy(projectId = "other-project"), paths) }
    }

    @Test fun changedFieldInventoryOrUnreviewedPathsCannotBeSaved() {
        val binding = stage()
        rejects { store().save(binding,
            mapOf("/data/foreign" to "x", paths[0] to "a"), paths) }
        store().save(binding, paths.associateWith { "ok" }, paths)
        rejects { store().read(binding, listOf(paths[0])) }
        rejects { store().save(binding, paths.associateWith { "x" },
            listOf(paths[0])) }
    }

    @Test fun repeatedSavesReplaceEncryptedValuesWithoutTempFiles() {
        val binding = stage()
        val repository = store()
        repository.save(binding, paths.associateWith { "first" }, paths)
        repository.save(binding, paths.associateWith { "second" }, paths)
        assertEquals("second", store().read(binding, paths).answers[paths[0]])
        assertEquals(listOf("binding.properties", "private-answers.aesgcm"),
            encryptedFile().parentFile.listFiles().orEmpty().map { it.name }.sorted())
    }

    private fun store() = EncryptedPrivateTextDraftStore(disk.root, jce)

    private fun encryptedFile() = java.io.File(disk.root,
        "recordwork-drafts/$project/$work/private-answers.aesgcm")

    private fun stage(): QuarantinedWorkDraft {
        val plan = HistoricalMappingPlan(
            workId = work, sourceSubmissionId = source, targetFormVersionId = target,
            workType = RecordWorkType.FOLLOW_UP, sourceXFormSha256 = null,
            targetXFormSha256 = "3".repeat(64),
            sourceFormId = "household", sourceVersion = "1",
            targetFormId = "household", targetVersion = "2",
            candidates = listOf(
                TargetFieldCandidate(paths[0], null,
                    setOf(MappingRisk.DIFFERENT_FORM_FOLLOW_UP), false),
                TargetFieldCandidate(paths[1], null,
                    setOf(MappingRisk.DIFFERENT_FORM_FOLLOW_UP), false)
            ),
            removedSourcePaths = emptySet(), globalRisks = setOf(
                MappingRisk.DIFFERENT_FORM_FOLLOW_UP
            )
        )
        val prepared = PreparedRecordPreview(
            workId = work, sourceSubmissionId = source, targetFormVersionId = target,
            targetFormId = "household", targetFormVersion = "2",
            type = RecordWorkType.FOLLOW_UP, mappingApprovalRequired = true,
            assessment = CrossVersionAssessment(
                work, source, target, RecordWorkType.FOLLOW_UP,
                "1".repeat(64), "3".repeat(64),
                "1", "2", 2, 2, 0, emptyList(),
                setOf(MappingIssue.CROSS_FORM_FOLLOW_UP)
            ),
            mappingPlan = plan,
            nativeStaging = NativeStagingEvidence(
                work, source, target, RecordWorkType.FOLLOW_UP,
                null, "3".repeat(64), true, false, 0, false
            ),
            sourceInstanceId = "uuid:9e1e9e90-5cc8-4e65-a5d0-acb5bdc1500d",
            visitKey = "visit-2"
        )
        val review = MappingReviewSnapshot(
            work, null, "3".repeat(64),
            paths.associateWith { MappingDecision.REENTER }, true
        )
        return QuarantinedWorkDraftVault(disk.root)
            .stage(project, user, prepared, review)
    }

    private fun rejects(block: () -> Unit) {
        var rejected = false
        try { block() } catch (_: Exception) { rejected = true }
        assertTrue("Invalid private draft operation must be rejected", rejected)
    }

    class TestAesGcmCipher : PrivateDraftCipher {
        private val key = SecretKeySpec(ByteArray(32) { 0x42.toByte() }, "AES")
        private val entropy = SecureRandom()
        override fun seal(cleartext: ByteArray, associatedData: ByteArray): ByteArray {
            val iv = ByteArray(12).apply { entropy.nextBytes(this) }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
            cipher.updateAAD(associatedData)
            return byteArrayOf(1, 12) + iv + cipher.doFinal(cleartext)
        }
        override fun open(envelope: ByteArray, associatedData: ByteArray): ByteArray {
            require(envelope.size >= 30 && envelope[0] == 1.toByte() &&
                envelope[1] == 12.toByte())
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key,
                GCMParameterSpec(128, envelope.copyOfRange(2, 14)))
            cipher.updateAAD(associatedData)
            return cipher.doFinal(envelope.copyOfRange(14, envelope.size))
        }
    }
}
