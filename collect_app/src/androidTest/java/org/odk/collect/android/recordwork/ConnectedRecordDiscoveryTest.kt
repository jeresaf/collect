package org.odk.collect.android.recordwork

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.odk.collect.settings.SettingsProvider
import org.odk.collect.settings.keys.ProjectKeys
import org.odk.collect.shared.settings.Settings
import java.net.URL
import java.security.KeyStore
import java.security.cert.CertificateFactory
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory

/**
 * U06-T04B-01: actual RecordWorkReadOnlyClient, real Android HTTPS connection,
 * real Spring Boot and PostgreSQL/Flyway, synthetic CI actor and revisions.
 * No MockWebServer or synthetic HttpURLConnection responses.
 */
@RunWith(AndroidJUnit4::class)
class ConnectedRecordDiscoveryTest {
    private val project = "81000000-0000-4000-8000-000000000001"
    private val original = "81000000-0000-4000-8000-000000000007"
    private val corrected = "81000000-0000-4000-8000-000000000008"
    private val base = "https://10.0.2.2:8443/v1/projects/$project"

    @Test
    fun actualRecordBrowserRetrievesOwnedHistoricalAndCurrentRevisions() {
        val client = clientFor("u06-connected-owner", "u06-disposable-only")
        val snapshot = client.load(project)
        assertEquals(2, snapshot.records.size)
        assertEquals(0, snapshot.pendingWork.size)
        val revisions = snapshot.records.associateBy { it.submissionId }
        assertEquals(setOf(original, corrected), revisions.keys)
        assertEquals(RemoteAmendmentRevision.SUPERSEDED, revisions.getValue(original).amendmentRevision)
        assertFalse(revisions.getValue(original).mayOfferAmendment)
        assertEquals(RemoteAmendmentRevision.CURRENT, revisions.getValue(corrected).amendmentRevision)
        assertTrue(revisions.getValue(corrected).mayOfferAmendment)
        assertFalse(revisions.getValue(original).instanceId == revisions.getValue(corrected).instanceId)
    }

    @Test
    fun wrongPasswordIsDeniedByRealBackend() {
        val client = clientFor("u06-connected-owner", "not-the-password")
        try {
            client.load(project)
            throw AssertionError("Wrong credentials must never retrieve owned records")
        } catch (e: ReadOnlyRecordException) {
            assertEquals(ReadOnlyRequestFailure.AUTH_REQUIRED, e.reason)
        }
    }

    private fun clientFor(username: String, password: String): RecordWorkReadOnlyClient {
        val settings = mock<Settings>()
        val provider = mock<SettingsProvider>()
        whenever(provider.getUnprotectedSettings(project)).thenReturn(settings)
        whenever(settings.getString(ProjectKeys.KEY_SERVER_URL)).thenReturn(base)
        whenever(settings.getString(ProjectKeys.KEY_USERNAME)).thenReturn(username)
        whenever(settings.getString(ProjectKeys.KEY_PASSWORD)).thenReturn(password)
        val ssl = ephemeralTrust()
        return RecordWorkReadOnlyClient(provider) { destination: URL ->
            (destination.openConnection() as HttpsURLConnection).also {
                // Scoped to the current test connection; no global or production changes.
                it.sslSocketFactory = ssl.socketFactory
            }
        }
    }

    private fun ephemeralTrust(): SSLContext {
        val certificate = InstrumentationRegistry.getInstrumentation()
            .context.assets.open("u06_t04a_ca.pem").use {
                CertificateFactory.getInstance("X.509").generateCertificate(it)
            }
        val store = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null)
            setCertificateEntry("disposable-u06-ca", certificate)
        }
        val managers = TrustManagerFactory.getInstance(
            TrustManagerFactory.getDefaultAlgorithm()
        ).apply { init(store) }.trustManagers
        return SSLContext.getInstance("TLS").apply { init(null, managers, null) }
    }
}
