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

    /**
     * U06-T04D-03A: real authenticated Spring/PostgreSQL records are rendered
     * through the same Android Button factory used by the production browser.
     * No fake record JSON, test-only activity or source XML is used.
     */
    @Test
    fun actualAndroidRowsRenderHistoricalAndCurrentWithoutEscalatingAmendment() {
        val snapshot = clientFor("u06-connected-owner", "u06-disposable-only")
            .load(project)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val rows = snapshot.records.associate { record ->
            var selected: RemoteRecord? = null
            val button = RecordWorkRecordRow.create(context, record) {
                selected = it
            }
            assertFalse(button.isAllCaps)
            assertTrue(button.text.toString().contains(record.submissionId))
            assertFalse(button.text.toString().contains("Synthetic V1"))
            assertFalse(button.text.toString().contains("PRIVATE_STALE_SOURCE"))
            assertTrue(button.performClick())
            assertEquals(record, selected)
            record.submissionId to button.text.toString()
        }
        assertEquals(setOf(original, corrected), rows.keys)
        assertTrue(rows.getValue(original).contains("Historical record"))
        assertTrue(rows.getValue(original).contains("choose its newer revision"))
        assertTrue(rows.getValue(corrected).contains("server rechecks"))
        assertFalse(snapshot.records.first { it.submissionId == original }.mayOfferAmendment)
        assertTrue(snapshot.records.first { it.submissionId == corrected }.mayOfferAmendment)
    }

    @Test
    fun unknownRevisionRowCannotPresentAnAmendmentOffer() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val unknown = RemoteRecord(
            submissionId = original,
            instanceId = "uuid:81000000-0000-4000-8000-000000000017",
            formId = "81000000-0000-4000-8000-000000000002",
            formVersionId = null,
            submittedAt = "2026-11-19T10:00:00Z"
        )
        val row = RecordWorkRecordRow.create(context, unknown) {}
        assertTrue(row.text.toString().contains("amendment creation blocked"))
        assertFalse(unknown.mayOfferAmendment)
    }

    /**
     * U06-T04D-03B1: validate the exact production browser-to-prepare intents
     * using owned records and pending work from actual Spring/PostgreSQL.
     * An Intent is NOT an authorization and this test does not start Activity.
     */
    @Test
    fun authenticatedServerRecordsProduceExactFailClosedPreparationIntents() {
        val snapshot = clientFor("u06-connected-owner", "u06-disposable-only").load(project)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val originalRecord = snapshot.records.single { it.submissionId == original }
        val currentRecord = snapshot.records.single { it.submissionId == corrected }

        val oldIntent = RecordWorkNavigation.record(context, project, originalRecord)
        val currentIntent = RecordWorkNavigation.record(context, project, currentRecord)
        assertEquals(project,
            oldIntent.getStringExtra(RecordWorkPrepareActivity.EXTRA_PROJECT))
        assertEquals(original,
            oldIntent.getStringExtra(RecordWorkPrepareActivity.EXTRA_SOURCE))
        assertFalse(oldIntent.getBooleanExtra(
            RecordWorkPrepareActivity.EXTRA_SOURCE_CAN_AMEND, true))
        assertTrue(currentIntent.getBooleanExtra(
            RecordWorkPrepareActivity.EXTRA_SOURCE_CAN_AMEND, false))
        assertEquals(corrected,
            currentIntent.getStringExtra(RecordWorkPrepareActivity.EXTRA_SOURCE))
        assertFalse(oldIntent.hasExtra(RecordWorkPrepareActivity.EXTRA_WORK))
        assertFalse(oldIntent.hasExtra(RecordWorkPrepareActivity.EXTRA_TARGET))
        assertFalse(oldIntent.hasExtra(RecordWorkPrepareActivity.EXTRA_TYPE))
        assertFalse(currentIntent.hasExtra(RecordWorkPrepareActivity.EXTRA_WORK))
        assertFalse(currentIntent.hasExtra(RecordWorkPrepareActivity.EXTRA_TARGET))

        // The raw source answers and authentication secrets are never extras.
        assertFalse(oldIntent.extras.toString().contains("u06-disposable-only"))
        assertFalse(oldIntent.extras.toString().contains("Synthetic V1"))
        assertFalse(oldIntent.extras.toString().contains("PRIVATE_STALE_SOURCE"))
    }

    @Test
    fun serverOwnedPendingWorkCreatesOnlyScopedWorkReferenceIntent() {
        val snapshot = clientFor("u06-connected-owner", "u06-disposable-only").load(project)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        // Run an unconditional synthetic pending contract assertion, because
        // the other API tests create pending work in nondeterministic order.
        // Also exercise every real pending item present in this authenticated
        // snapshot without assuming one exists yet.
        val reference = RemoteVisitWork(
            id = "81000000-0000-4000-8000-000000000090",
            sourceSubmissionId = original,
            targetFormVersionId = "81000000-0000-4000-8000-000000000004",
            type = RecordWorkType.FOLLOW_UP,
            visitKey = "independent-visit"
        )
        (listOf(reference) + snapshot.pendingWork).forEach { work ->
            val intent = RecordWorkNavigation.pending(context, project, work)
            assertEquals(project, intent.getStringExtra(RecordWorkPrepareActivity.EXTRA_PROJECT))
            assertEquals(work.sourceSubmissionId,
                intent.getStringExtra(RecordWorkPrepareActivity.EXTRA_SOURCE))
            assertEquals(work.id,
                intent.getStringExtra(RecordWorkPrepareActivity.EXTRA_WORK))
            assertEquals(work.targetFormVersionId,
                intent.getStringExtra(RecordWorkPrepareActivity.EXTRA_TARGET))
            assertEquals(work.type.name,
                intent.getStringExtra(RecordWorkPrepareActivity.EXTRA_TYPE))
            assertFalse(intent.hasExtra(RecordWorkPrepareActivity.EXTRA_SOURCE_CAN_AMEND))
            assertFalse(intent.extras.toString().contains("u06-disposable-only"))
        }
    }

    @Test
    fun malformedServerRecordIdentifierCannotMakeNavigationIntent() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val invalid = RemoteRecord(
            submissionId = "../private-source-xml",
            instanceId = "uuid:81000000-0000-4000-8000-000000000017",
            formId = original,
            formVersionId = null,
            submittedAt = "2026-11-23T00:00:00Z",
            amendmentRevision = RemoteAmendmentRevision.CURRENT
        )
        var rejected = false
        try {
            RecordWorkNavigation.record(context, project, invalid)
        } catch (_: IllegalArgumentException) {
            rejected = true
        }
        assertTrue("Navigation must reject noncanonical server IDs", rejected)
    }


    /**
     * U06-T04D-03C1: genuine authorized READY follow-up work is independently
     * retrieved, rendered using the browser's production Android Button and
     * selected without silently changing it into an amendment.
     */
    @Test
    fun genuineReadyVisitRendersAsIndependentPendingWorkAndKeepsServerId() {
        val request = NewRecordWork(
            sourceSubmissionId = original,
            targetFormVersionId = "81000000-0000-4000-8000-000000000004",
            workType = RecordWorkType.FOLLOW_UP,
            clientRequestId = "u06-ui-pending-request-" + UUID.randomUUID(),
            visitKey = "u06-ui-pending-" + UUID.randomUUID()
        )
        val preparer = preparationClient()
        val created = preparer.create(project, request)
        assertEquals(created.id, preparer.create(project, request).id)
        val snapshot = clientFor("u06-connected-owner", "u06-disposable-only").load(project)
        val ready = snapshot.pendingWork.single { it.id == created.id }
        assertEquals(RecordWorkType.FOLLOW_UP, ready.type)
        assertEquals(original, ready.sourceSubmissionId)
        assertEquals(request.visitKey, ready.visitKey)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        var selected: RemoteVisitWork? = null
        val button = RecordWorkPendingRow.create(context, ready) { selected = it }
        assertTrue(button.text.toString().contains("Independent follow-up visit"))
        assertTrue(button.text.toString().contains(ready.id))
        assertTrue(button.text.toString().contains(ready.visitKey.orEmpty()))
        assertFalse(button.text.toString().contains("Pending amendment"))
        assertFalse(button.text.toString().contains("Synthetic V1"))
        assertFalse(button.isAllCaps)
        assertTrue(button.performClick())
        assertEquals(ready, selected)
        val intent = RecordWorkNavigation.pending(context, project, requireNotNull(selected))
        assertEquals(ready.id, intent.getStringExtra(RecordWorkPrepareActivity.EXTRA_WORK))
        assertEquals(ready.sourceSubmissionId,
            intent.getStringExtra(RecordWorkPrepareActivity.EXTRA_SOURCE))
        assertEquals(ready.targetFormVersionId,
            intent.getStringExtra(RecordWorkPrepareActivity.EXTRA_TARGET))
        assertEquals(RecordWorkType.FOLLOW_UP.name,
            intent.getStringExtra(RecordWorkPrepareActivity.EXTRA_TYPE))
        assertFalse(intent.hasExtra(RecordWorkPrepareActivity.EXTRA_SOURCE_CAN_AMEND))
    }

    @Test
    fun amendmentWorkRowCannotBeConfusedWithIndependentVisit() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val work = RemoteVisitWork(
            id = "81000000-0000-4000-8000-000000000090",
            sourceSubmissionId = corrected,
            targetFormVersionId = "81000000-0000-4000-8000-000000000004",
            type = RecordWorkType.AMENDMENT,
            visitKey = null
        )
        val button = RecordWorkPendingRow.create(context, work) {}
        assertTrue(button.text.toString().contains("Pending amendment"))
        assertFalse(button.text.toString().contains("Independent follow-up visit"))
        assertFalse(button.text.toString().contains("Visit:"))
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
