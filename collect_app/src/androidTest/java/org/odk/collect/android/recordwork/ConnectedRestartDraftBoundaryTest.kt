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
import java.net.URL
import java.security.KeyStore
import java.security.cert.CertificateFactory
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory

/**
 * CI runs write and reopen in separate AndroidJUnitRunner invocations, with
 * `am force-stop` between them. Never run these phases in a single test
 * process and never reinstall/uninstall the application between phases.
 *
 * This proves persisted AES-GCM no-backup ciphertext and the live stale 409
 * across a genuine app process boundary. The fixture is a quarantined
 * synthetic ciphertext sample, NOT a native ODK editable instance.
 */
@RunWith(AndroidJUnit4::class)
class ConnectedRestartDraftBoundaryTest {
    private val project = "82000000-0000-4000-8000-000000000001"
    private val work = "82000000-0000-4000-8000-000000000009"
    private val source = "82000000-0000-4000-8000-000000000007"
    private val target = "82000000-0000-4000-8000-000000000004"
    private val actor = "u06-connected-owner"
    private val privateText = "SYNTHETIC_OFFLINE_ANSWER_NOT_FOR_UPLOAD"
    private val aad = "$project\n$work\n$actor".toByteArray(Charsets.UTF_8)

    private fun marker(): File = File(
        InstrumentationRegistry.getInstrumentation().targetContext.noBackupFilesDir,
        "u06-t04c02b-stale-work.aesgcm"
    )

    @Test
    fun phaseOneWriteOnlyEncryptedNoBackupCiphertext() {
        val file = marker()
        assertTrue(file.parentFile?.isDirectory == true)
        assertFalse("The isolated test must start without old ciphertext", file.exists())
        val bytes = AndroidKeyStorePrivateDraftCipher()
            .seal(privateText.toByteArray(Charsets.UTF_8), aad)
        file.writeBytes(bytes)
        assertTrue(file.exists())
        assertFalse(file.readBytes().toString(Charsets.ISO_8859_1).contains(privateText))
        assertArrayEquals(bytes, file.readBytes())
    }

    @Test
    fun phaseTwoAfterForceStopPreservesAnswersOnLiveConflict() {
        val file = marker()
        assertTrue("Phase 1 ciphertext was not retained across process restart", file.exists())
        val before = file.readBytes()
        val cipher = AndroidKeyStorePrivateDraftCipher()
        assertEquals(privateText, String(cipher.open(before, aad), Charsets.UTF_8))
        val wrongContext = "$project\n$work\nunauthorized-actor"
            .toByteArray(Charsets.UTF_8)
        var blocked = false
        try {
            cipher.open(before, wrongContext)
        } catch (_: Exception) { blocked = true }
        assertTrue("Actor binding must survive the process boundary", blocked)

        val settings = mock<Settings>()
        val provider = mock<SettingsProvider>()
        whenever(provider.getUnprotectedSettings(project)).thenReturn(settings)
        whenever(settings.getString(ProjectKeys.KEY_SERVER_URL))
            .thenReturn("https://10.0.2.2:8443/v1/projects/$project")
        whenever(settings.getString(ProjectKeys.KEY_USERNAME)).thenReturn(actor)
        whenever(settings.getString(ProjectKeys.KEY_PASSWORD))
            .thenReturn("u06-disposable-only")
        val ssl = scopedTrust()
        val client = RecordWorkPreparationClient(
            settings = provider,
            openConnection = { endpoint: URL ->
                (endpoint.openConnection() as HttpsURLConnection).also {
                    it.sslSocketFactory = ssl.socketFactory
                }
            }
        )
        val policy = try {
            client.prepare(project, work, source, target, RecordWorkType.AMENDMENT)
            throw AssertionError("The real backend must reject stale work with 409")
        } catch (error: PreparationException) {
            assertEquals(PreparationError.CONFLICT, error.reason)
            ProtectedWorkRecovery.forFailure(error)
        }
        assertEquals(ProtectedWorkFailure.STALE_OR_COMPLETED, policy.failure)
        assertTrue(policy.preserveSavedEncryptedDraft)
        assertFalse(policy.canOpenEditor)
        assertFalse(policy.canSave)
        assertFalse(policy.canUpload)
        assertFalse(policy.mayAutomaticallyRetargetSource)
        assertFalse(policy.mayAutomaticallyRetrySubmission)
        assertArrayEquals(before, file.readBytes())
        assertEquals(privateText, String(cipher.open(file.readBytes(), aad), Charsets.UTF_8))
        assertTrue("Test cleanup must succeed", file.delete())
    }

    private fun scopedTrust(): SSLContext {
        val certificate = InstrumentationRegistry.getInstrumentation()
            .context.assets.open("u06_t04a_ca.pem").use {
                CertificateFactory.getInstance("X.509").generateCertificate(it)
            }
        val store = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null)
            setCertificateEntry("u06-ephemeral", certificate)
        }
        val managers = TrustManagerFactory.getInstance(
            TrustManagerFactory.getDefaultAlgorithm()
        ).apply { init(store) }.trustManagers
        return SSLContext.getInstance("TLS").apply { init(null, managers, null) }
    }
}
