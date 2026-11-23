package org.odk.collect.android.recordwork

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.odk.collect.settings.SettingsProvider
import org.odk.collect.settings.keys.ProjectKeys
import org.odk.collect.shared.settings.Settings
import java.io.File
import java.io.IOException
import java.net.URL
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.util.UUID
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory

/**
 * U06-T04C-02A: live HTTPS/PostgreSQL 409 after a stored revision and
 * Android Keystore ciphertext retention. Offline path is transport-injected,
 * not a physical disconnection; no editor/save/upload authority is granted.
 */
@RunWith(AndroidJUnit4::class)
class ConnectedStaleWorkRecoveryTest {
    private val project = "82000000-0000-4000-8000-000000000001"
    private val source = "82000000-0000-4000-8000-000000000007"
    private val work = "82000000-0000-4000-8000-000000000009"
    private val target = "82000000-0000-4000-8000-000000000004"
    private val actor = "u06-connected-owner"
    private val base = "https://10.0.2.2:8443/v1/projects/$project"

    @Test
    fun staleServerWorkCannotUnlockOrOverwritePrivateEncryptedDraft() {
        val directory = File(
            InstrumentationRegistry.getInstrumentation().targetContext.noBackupFilesDir,
            "u06-conflict-" + UUID.randomUUID()
        )
        assertTrue(directory.mkdirs())
        val file = File(directory, "inert-ciphertext.aesgcm")
        val cipher = AndroidKeyStorePrivateDraftCipher()
        val aad = "$project\n$work\n$actor".toByteArray()
        val wrongActor = "$project\n$work\nother-actor".toByteArray()
        val answer = "PRIVATE_LOCAL_DRAFT_ANSWER_DO_NOT_UPLOAD".toByteArray()
        try {
            file.writeBytes(cipher.seal(answer, aad))
            val originalBytes = file.readBytes()
            assertFalse(originalBytes.toString(Charsets.ISO_8859_1)
                .contains("PRIVATE_LOCAL_DRAFT_ANSWER_DO_NOT_UPLOAD"))

            val offline = client { throw IOException("Disconnected transport") }
            val networkPolicy = try {
                offline.prepare(project, work, source, target, RecordWorkType.AMENDMENT)
                throw AssertionError("Offline work must not prepare")
            } catch (error: PreparationException) {
                assertEquals(PreparationError.NETWORK, error.reason)
                ProtectedWorkRecovery.forFailure(error)
            }
            assertEquals(ProtectedWorkFailure.OFFLINE_RETRY, networkPolicy.failure)
            assertFalse(networkPolicy.canOpenEditor)
            assertFalse(networkPolicy.canSave)
            assertFalse(networkPolicy.canUpload)
            assertTrue(networkPolicy.preserveSavedEncryptedDraft)
            assertTrue(originalBytes.contentEquals(file.readBytes()))

            val live = client { endpoint ->
                (endpoint.openConnection() as HttpsURLConnection).also {
                    it.sslSocketFactory = scopedTrust().socketFactory
                }
            }
            val conflict = try {
                live.prepare(project, work, source, target, RecordWorkType.AMENDMENT)
                throw AssertionError("An intervening source revision must yield 409")
            } catch (error: PreparationException) {
                assertEquals(PreparationError.CONFLICT, error.reason)
                ProtectedWorkRecovery.forFailure(error)
            }
            assertEquals(ProtectedWorkFailure.STALE_OR_COMPLETED, conflict.failure)
            assertTrue(conflict.mayManuallyReviewServerRecords)
            assertTrue(conflict.preserveSavedEncryptedDraft)
            assertFalse(conflict.canOpenEditor)
            assertFalse(conflict.canSave)
            assertFalse(conflict.canFinalize)
            assertFalse(conflict.canUpload)
            assertFalse(conflict.mayAutomaticallyRetargetSource)
            assertFalse(conflict.mayAutomaticallyRetrySubmission)
            assertFalse(conflict.message.contains("PRIVATE_STALE_SOURCE"))
            assertTrue(originalBytes.contentEquals(file.readBytes()))
            assertTrue(answer.contentEquals(cipher.open(file.readBytes(), aad)))
            var denied = false
            try { cipher.open(file.readBytes(), wrongActor) }
            catch (_: Exception) { denied = true }
            assertTrue("Wrong actor must not decrypt", denied)
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun client(open: (URL) -> java.net.HttpURLConnection): RecordWorkPreparationClient {
        val values = mock<Settings>()
        val provider = mock<SettingsProvider>()
        whenever(provider.getUnprotectedSettings(project)).thenReturn(values)
        whenever(values.getString(ProjectKeys.KEY_SERVER_URL)).thenReturn(base)
        whenever(values.getString(ProjectKeys.KEY_USERNAME)).thenReturn(actor)
        whenever(values.getString(ProjectKeys.KEY_PASSWORD)).thenReturn("u06-disposable-only")
        return RecordWorkPreparationClient(settings = provider, openConnection = open)
    }

    private fun scopedTrust(): SSLContext {
        val cert = InstrumentationRegistry.getInstrumentation().context.assets
            .open("u06_t04a_ca.pem").use {
                CertificateFactory.getInstance("X.509").generateCertificate(it)
            }
        val store = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null)
            setCertificateEntry("temporary-u06", cert)
        }
        val managers = TrustManagerFactory.getInstance(
            TrustManagerFactory.getDefaultAlgorithm()
        ).apply { init(store) }.trustManagers
        return SSLContext.getInstance("TLS").apply { init(null, managers, null) }
    }
}
