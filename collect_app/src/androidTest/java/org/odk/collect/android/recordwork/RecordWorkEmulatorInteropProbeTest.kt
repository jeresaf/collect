package org.odk.collect.android.recordwork

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.odk.collect.settings.SettingsProvider
import org.odk.collect.settings.keys.ProjectKeys
import org.odk.collect.shared.settings.Settings
import java.io.ByteArrayInputStream
import java.io.File
import java.net.HttpURLConnection
import java.nio.charset.StandardCharsets
import java.util.UUID

/**
 * Runs on a booted Android emulator with genuine Android Base64, filesystem
 * and AndroidKeyStore. Only the transport responses are synthetic. These
 * tests must NOT be represented as a live Spring/PostgreSQL end-to-end run.
 */
@RunWith(AndroidJUnit4::class)
class RecordWorkEmulatorInteropProbeTest {
    private val project = "c77a446e-18b6-4a5a-9f52-aa12fedba010"
    private val source = "a8c07f51-b4c1-40a4-b31c-956d2ca5e048"
    private val latest = "2c85d9fe-7b5a-45ce-b843-d99f647d12d9"
    private val target = "5a6f0bf6-795c-4d91-9861-16910542b4f1"
    private val work = "e6bddba9-e98b-49cb-8bed-dce91d400520"
    private val base = "https://collect.example.org/v1/projects/$project"

    @Test
    fun authenticatedRecordDiscoveryDistinguishesSupersededAndCurrentOnAndroid() {
        val (provider, _) = settings()
        val urls = mutableListOf<String>()
        val client = RecordWorkReadOnlyClient(provider) { destination ->
            urls += destination.toString()
            val response = if (destination.toString().endsWith("/records")) {
                """
                [
                  {"submissionId":"$source",
                   "instanceId":"uuid:9e1e9e90-5cc8-4e65-a5d0-acb5bdc1500d",
                   "formId":"$source","formVersionId":"$target",
                   "submittedAt":"2026-11-23T10:00:00Z","amendmentSuperseded":true},
                  {"submissionId":"$latest",
                   "instanceId":"uuid:cc29e1f4-7c5e-40ed-9ce7-384508378999",
                   "formId":"$source","formVersionId":"$target",
                   "submittedAt":"2026-11-23T11:00:00Z","amendmentSuperseded":false}
                ]
                """.trimIndent()
            } else "[]"
            response(200, response)
        }
        val snapshot = client.load(project)
        assertEquals(listOf("$base/records", "$base/record-work-items"), urls)
        assertEquals(2, snapshot.records.size)
        assertEquals(RemoteAmendmentRevision.SUPERSEDED,
            snapshot.records[0].amendmentRevision)
        assertFalse(snapshot.records[0].mayOfferAmendment)
        assertEquals(RemoteAmendmentRevision.CURRENT,
            snapshot.records[1].amendmentRevision)
        assertTrue(snapshot.records[1].mayOfferAmendment)
        assertFalse(snapshot.records[0].instanceId == snapshot.records[1].instanceId)
    }

    @Test
    fun stalePreparationReturnsTypedConflictAndNeverReopensEditor() {
        val (provider, _) = settings()
        val client = RecordWorkPreparationClient(provider) {
            response(409, """{"message":"Source amended since work was created",
                "sourceXml":"PRIVATE_ANSWER_MUST_NOT_RENDER"}""")
        }
        val binding = QuarantinedWorkDraft(
            projectId = project, actorContextSha256 =
                QuarantinedWorkDraftVault.sha256(
                    project + "\n" + RecordWorkApiPaths.fromProjectUrl(base).records() +
                        "\n" + "enumerator"
                ),
            workId = work, sourceSubmissionId = source,
            sourceInstanceId = "uuid:9e1e9e90-5cc8-4e65-a5d0-acb5bdc1500d",
            targetFormVersionId = target, targetFormId = "household",
            targetVersion = "2", workType = RecordWorkType.AMENDMENT,
            visitKey = null,
            reservedInstanceId = "uuid:b2edb2a9-3250-479a-a30a-0cdb1586b055",
            sourceXmlSha256 = "b".repeat(64),
            sourceXFormSha256 = "c".repeat(64),
            targetXFormSha256 = "d".repeat(64),
            reviewedPaths = 1, validationTransfers = 0,
            requiresDeprecatedId = true
        )
        val recovery = try {
            client.authorizePrivateTextForm(project, binding)
            fail("HTTP 409 must reject old offline work")
            throw AssertionError("No stale draft may reopen")
        } catch (error: PreparationException) {
            assertEquals(PreparationError.CONFLICT, error.reason)
            ProtectedWorkRecovery.forFailure(error)
        }
        assertEquals(ProtectedWorkFailure.STALE_OR_COMPLETED, recovery.failure)
        assertTrue(recovery.mayManuallyReviewServerRecords)
        assertTrue(recovery.preserveSavedEncryptedDraft)
        assertFalse(recovery.canOpenEditor)
        assertFalse(recovery.canSave)
        assertFalse(recovery.canUpload)
        assertFalse(recovery.mayAutomaticallyRetargetSource)
        assertFalse(recovery.mayAutomaticallyRetrySubmission)
        assertFalse(recovery.message.contains("PRIVATE_ANSWER_MUST_NOT_RENDER"))
    }

    @Test
    fun androidKeystoreCiphertextSurvivesConflictAndIsBoundToOriginalActor() {
        val root = InstrumentationRegistry.getInstrumentation()
            .targetContext.noBackupFilesDir
        val directory = File(root, "m4c-emulator-interop-${UUID.randomUUID()}")
        assertTrue(directory.mkdirs())
        val ciphertext = File(directory, "preserved-draft.aesgcm")
        val cipher = AndroidKeyStorePrivateDraftCipher()
        val answer = "SENSITIVE_SYNTHETIC_HOUSEHOLD_ANSWER"
            .toByteArray(StandardCharsets.UTF_8)
        val originalActor = "$project\n$work\noriginal-enumerator"
            .toByteArray(StandardCharsets.UTF_8)
        val otherActor = "$project\n$work\nother-enumerator"
            .toByteArray(StandardCharsets.UTF_8)
        try {
            ciphertext.writeBytes(cipher.seal(answer, originalActor))
            val stored = ciphertext.readBytes()
            assertFalse(stored.toString(StandardCharsets.ISO_8859_1)
                .contains("SENSITIVE_SYNTHETIC_HOUSEHOLD_ANSWER"))
            val recovery = ProtectedWorkRecovery.forFailure(
                PreparationException(PreparationError.CONFLICT)
            )
            assertTrue(recovery.preserveSavedEncryptedDraft)
            assertFalse(recovery.canSave)
            assertFalse(recovery.canUpload)
            assertTrue(stored.contentEquals(ciphertext.readBytes()))
            assertTrue(answer.contentEquals(cipher.open(ciphertext.readBytes(), originalActor)))
            var otherDenied = false
            try {
                cipher.open(ciphertext.readBytes(), otherActor)
            } catch (_: Exception) {
                otherDenied = true
            }
            assertTrue("Ciphertext must not open for a different actor", otherDenied)
            assertTrue(stored.contentEquals(ciphertext.readBytes()))
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun settings(): Pair<SettingsProvider, Settings> {
        val provider = mock<SettingsProvider>()
        val values = mock<Settings>()
        whenever(provider.getUnprotectedSettings(project)).thenReturn(values)
        whenever(values.getString(ProjectKeys.KEY_SERVER_URL)).thenReturn(base)
        whenever(values.getString(ProjectKeys.KEY_USERNAME)).thenReturn("enumerator")
        whenever(values.getString(ProjectKeys.KEY_PASSWORD)).thenReturn("emulator-synthetic-only")
        return provider to values
    }

    private fun response(status: Int, body: String): HttpURLConnection =
        mock<HttpURLConnection>().also { http ->
            whenever(http.responseCode).thenReturn(status)
            whenever(http.contentType).thenReturn("application/json")
            whenever(http.inputStream).thenReturn(ByteArrayInputStream(
                body.toByteArray(StandardCharsets.UTF_8)))
        }
}
