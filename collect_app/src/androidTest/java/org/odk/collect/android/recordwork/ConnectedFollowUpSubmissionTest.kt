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
import java.io.IOException
import java.net.URL
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.util.UUID
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import android.util.Base64

/**
 * U06-T04C-03A: a real Android-emulator HTTPS multipart upload of a synthetic
 * independent follow-up observation, with a backend-generated work-item ID.
 * Invoked AFTER all read-only/admission tests in its own instrumentation run.
 *
 * The upload is a deliberately minimal native HttpURLConnection transport
 * probe; it does not claim integration with ODK's full InstanceSubmitter,
 * the native editable form lifecycle. This test exercises binary/media
 * and repeat parsing only at real HTTPS intake; not native ODK InstanceSubmitter.
 */
@RunWith(AndroidJUnit4::class)
class ConnectedFollowUpSubmissionTest {
    private val project = "81000000-0000-4000-8000-000000000001"
    private val source = "81000000-0000-4000-8000-000000000007"
    private val target = "81000000-0000-4000-8000-000000000004"
    private val base = "https://10.0.2.2:8443/v1/projects/$project"
    private val username = "u06-connected-owner"
    private val password = "u06-disposable-only"

    @Test
    fun independentFollowUpIsCreatedOnceAndOriginalRevisionRemainsImmutable() {
        val ssl = trustedContext()
        val settings = mock<Settings>()
        val provider = mock<SettingsProvider>()
        whenever(provider.getUnprotectedSettings(project)).thenReturn(settings)
        whenever(settings.getString(ProjectKeys.KEY_SERVER_URL)).thenReturn(base)
        whenever(settings.getString(ProjectKeys.KEY_USERNAME)).thenReturn(username)
        whenever(settings.getString(ProjectKeys.KEY_PASSWORD)).thenReturn(password)
        val open = { url: URL ->
            (url.openConnection() as HttpsURLConnection).also {
                it.sslSocketFactory = ssl.socketFactory
            }
        }
        val preparer = RecordWorkPreparationClient(
            settings = provider,
            openConnection = open
        )
        val visitKey = "completed-visit-" + UUID.randomUUID()
        val work = preparer.create(project, NewRecordWork(
            sourceSubmissionId = source,
            targetFormVersionId = target,
            workType = RecordWorkType.FOLLOW_UP,
            clientRequestId = "completed-work-" + UUID.randomUUID(),
            visitKey = visitKey
        ))
        assertEquals(source, work.sourceSubmissionId)
        assertEquals(RecordWorkType.FOLLOW_UP, work.type)
        assertEquals(visitKey, work.visitKey)

        val instance = "uuid:" + UUID.randomUUID()
        val body = """
            <data id="u06-synthetic-household" version="2">
              <household_name>Synthetic independent later visit</household_name>
              <photo>u06-followup-photo.jpg</photo>
              <children><child_name>Amina</child_name><child_age_text>seven</child_age_text></children>
              <children><child_name>Juma</child_name><child_age_text>four</child_age_text></children>
              <meta><instanceID>$instance</instanceID></meta>
            </data>
        """.trimIndent()
        // U06-T04C-03C: the real server MUST commit the multipart POST first.
        // The test-only transport boundary then discards its acknowledgement,
        // producing an ambiguous IOException at the Android caller.
        var lostAcknowledgement = false
        try {
            upload(ssl, work.id, body, discardCommittedAcknowledgement = true)
            fail("The caller must not receive the committed 201 acknowledgement")
        } catch (expected: IOException) {
            assertEquals("Synthetic post-commit acknowledgement loss", expected.message)
            lostAcknowledgement = true
        }
        assertTrue(lostAcknowledgement)

        // Never interpret a lost acknowledgement as proof that submission
        // failed. Query the same actor/project over fresh verified HTTPS and
        // reconcile the stable instanceID before attempting an identical retry.
        val reconciled = RecordWorkReadOnlyClient(provider, open).load(project)
        assertEquals(3, reconciled.records.size)
        val committed = reconciled.records.single { it.instanceId == instance }
        assertEquals(target, committed.formVersionId)
        assertFalse(reconciled.pendingWork.any { it.id == work.id })

        // Deliberately force a retry to prove the backend cannot create a
        // duplicate observation or second link from the same work/instance.
        assertEquals(409, upload(ssl, work.id, body))

        val snapshot = RecordWorkReadOnlyClient(provider, open).load(project)
        assertEquals(3, snapshot.records.size)
        assertEquals(committed.submissionId,
            snapshot.records.single { it.instanceId == instance }.submissionId)
        assertTrue(snapshot.records.any { it.instanceId == instance &&
            it.formVersionId == target })
        val prior = snapshot.records.single { it.submissionId == source }
        assertEquals(RemoteAmendmentRevision.SUPERSEDED, prior.amendmentRevision)
        assertFalse(prior.mayOfferAmendment)
        assertFalse(snapshot.pendingWork.any { it.id == work.id })
    }

    private fun upload(\n        ssl: SSLContext, workId: String, xml: String,\n        discardCommittedAcknowledgement: Boolean = false\n    ): Int {
        val boundary = "u06multipart" + UUID.randomUUID().toString().replace("-", "")
        val head = (
            "--$boundary\r\n" +
            "Content-Disposition: form-data; name=\"xml_submission_file\"; filename=\"followup.xml\"\r\n" +
            "Content-Type: text/xml\r\n\r\n"
        ).toByteArray(Charsets.UTF_8)
        val end = "\r\n--$boundary--\r\n".toByteArray(Charsets.UTF_8)
        val payload = xml.toByteArray(Charsets.UTF_8)
        // Small synthetic JPEG-signature fixture; byte-for-byte persistence is
        // checked in the runner, without logging media or private answers.
        val photo = byteArrayOf(
            0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0xe0.toByte(),
            0x00, 0x10, 0x4a, 0x46, 0x49, 0x46, 0x00, 0x01,
            0x11, 0x22, 0x33, 0x44, 0xff.toByte(), 0xd9.toByte()
        )
        val attachmentHeader = (
            "\r\n--$boundary\r\n" +
            "Content-Disposition: form-data; name=\"photo\"; filename=\"u06-followup-photo.jpg\"\r\n" +
            "Content-Type: image/jpeg\r\n\r\n"
        ).toByteArray(Charsets.UTF_8)
        val connection = URL("$base/submission").openConnection() as HttpsURLConnection
        connection.sslSocketFactory = ssl.socketFactory
        connection.instanceFollowRedirects = false
        connection.connectTimeout = 20_000
        connection.readTimeout = 30_000
        connection.requestMethod = "POST"
        connection.doOutput = true
        connection.useCaches = false
        connection.setRequestProperty("Authorization", "Basic " +
            Base64.encodeToString("$username:$password".toByteArray(), Base64.NO_WRAP))
        connection.setRequestProperty("X-DataCollector-Work-Item-Id", workId)
        connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
        connection.setFixedLengthStreamingMode(head.size + payload.size + attachmentHeader.size + photo.size + end.size)
        try {
            connection.outputStream.use {
                it.write(head)
                it.write(payload)
                it.write(attachmentHeader)
                it.write(photo)
                it.write(end)
            }
            val status = connection.responseCode
            // Responses are deliberately not logged; they may contain identifiers.
            connection.getInputStreamOrError()?.use { stream ->
                val buffer = ByteArray(8192)
                while (stream.read(buffer) >= 0) { /* drain safely */ }
            }
            if (discardCommittedAcknowledgement && status == 201) {
                // CI-only injected loss after a real 201. The calling app
                // receives no receipt/status; do not silently treat as a
                // completed network call or claim real packet-loss testing.
                throw IOException("Synthetic post-commit acknowledgement loss")
            }
            return status
        } finally {
            connection.disconnect()
        }
    }

    private fun HttpsURLConnection.getInputStreamOrError() =
        if (responseCode in 200..299) inputStream else errorStream

    private fun trustedContext(): SSLContext {
        val certificate = InstrumentationRegistry.getInstrumentation().context.assets
            .open("u06_t04a_ca.pem").use {
                CertificateFactory.getInstance("X.509").generateCertificate(it)
            }
        val store = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null)
            setCertificateEntry("ci-only", certificate)
        }
        val managers = TrustManagerFactory.getInstance(
            TrustManagerFactory.getDefaultAlgorithm()
        ).apply { init(store) }.trustManagers
        return SSLContext.getInstance("TLS").apply { init(null, managers, null) }
    }
}
