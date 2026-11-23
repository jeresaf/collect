package org.odk.collect.android.recordwork

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import java.util.UUID
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
        // A separate amendment admission test may have created work already.
        assertTrue(snapshot.pendingWork.filter { it.type == RecordWorkType.AMENDMENT }
            .all { it.sourceSubmissionId == corrected })
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

    /**
     * U06-T04B-02: actual app preparation client and real server-owned work IDs.
     * Historical source is denied transactionally; current source admits one
     * distinct work item. No editor, answer transfer or upload is authorized.
     */
    @Test
    fun realBackendDeniesSupersededAndAdmitsCurrentAmendment() {
        val client = preparationClient()
        val targetVersion = "81000000-0000-4000-8000-000000000004"
        val staleRequest = NewRecordWork(
            original, targetVersion, RecordWorkType.AMENDMENT,
            "u06-stale-" + UUID.randomUUID()
        )
        try {
            client.create(project, staleRequest)
            fail("Superseded V1 must never receive new amendment work")
        } catch (error: PreparationException) {
            assertEquals(PreparationError.CONFLICT, error.reason)
        }
        val freshRequest = NewRecordWork(
            corrected, targetVersion, RecordWorkType.AMENDMENT,
            "u06-current-" + UUID.randomUUID()
        )
        val created = client.create(project, freshRequest)
        assertEquals(corrected, created.sourceSubmissionId)
        assertEquals(targetVersion, created.targetFormVersionId)
        assertEquals(RecordWorkType.AMENDMENT, created.type)
        assertEquals(null, created.visitKey)
        assertTrue(UUID.fromString(created.id).toString() == created.id)
        val repeated = client.create(project, freshRequest)
        assertEquals(created.id, repeated.id)

        val snapshot = clientFor("u06-connected-owner", "u06-disposable-only")
            .load(project)
        assertTrue(snapshot.pendingWork.any { it.id == created.id &&
            it.sourceSubmissionId == corrected })
        assertFalse(snapshot.pendingWork.any { it.sourceSubmissionId == original &&
            it.type == RecordWorkType.AMENDMENT })
    }

    /**
     * U06-T04C-01: a second household visit is independent from amendment.
     * A historical V1 observation is eligible for FOLLOW_UP despite being
     * superseded for AMENDMENT. Work creation never uploads an observation.
     */
    @Test
    fun historicalHouseholdMayScheduleIndependentSecondVisitWithoutAmendingV1() {
        val client = preparationClient()
        val targetVersion = "81000000-0000-4000-8000-000000000004"
        val request = NewRecordWork(
            sourceSubmissionId = original,
            targetFormVersionId = targetVersion,
            workType = RecordWorkType.FOLLOW_UP,
            clientRequestId = "u06-visit-request-" + UUID.randomUUID(),
            visitKey = "second-household-visit"
        )
        val created = client.create(project, request)
        assertEquals(original, created.sourceSubmissionId)
        assertEquals(targetVersion, created.targetFormVersionId)
        assertEquals(RecordWorkType.FOLLOW_UP, created.type)
        assertEquals("second-household-visit", created.visitKey)
        assertEquals(created.id, client.create(project, request).id)
        val snapshot = clientFor("u06-connected-owner", "u06-disposable-only")
            .load(project)
        assertTrue(snapshot.pendingWork.any {
            it.id == created.id && it.sourceSubmissionId == original &&
                it.type == RecordWorkType.FOLLOW_UP &&
                it.visitKey == "second-household-visit"
        })
        val originalHistory = snapshot.records.first { it.submissionId == original }
        assertEquals(RemoteAmendmentRevision.SUPERSEDED, originalHistory.amendmentRevision)
        assertFalse(originalHistory.mayOfferAmendment)
    }

    private fun preparationClient(): RecordWorkPreparationClient {
        val settings = mock<Settings>()
        val provider = mock<SettingsProvider>()
        whenever(provider.getUnprotectedSettings(project)).thenReturn(settings)
        whenever(settings.getString(ProjectKeys.KEY_SERVER_URL)).thenReturn(base)
        whenever(settings.getString(ProjectKeys.KEY_USERNAME))
            .thenReturn("u06-connected-owner")
        whenever(settings.getString(ProjectKeys.KEY_PASSWORD))
            .thenReturn("u06-disposable-only")
        val ssl = ephemeralTrust()
        return RecordWorkPreparationClient(
            settings = provider,
            openConnection = { destination: URL ->
                (destination.openConnection() as HttpsURLConnection).also {
                    it.sslSocketFactory = ssl.socketFactory
                }
            }
        )
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
