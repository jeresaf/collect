package org.odk.collect.android.recordwork

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.startsWith
import org.mockito.Mockito.verify
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.odk.collect.settings.SettingsProvider
import org.odk.collect.settings.keys.ProjectKeys
import org.odk.collect.shared.settings.Settings
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

@RunWith(RobolectricTestRunner::class)
class RecordWorkReadOnlyClientTest {
    private val projectId = "c77a446e-18b6-4a5a-9f52-aa12fedba010"
    private val submissionId = "a8c07f51-b4c1-40a4-b31c-956d2ca5e048"
    private val targetId = "5a6f0bf6-795c-4d91-9861-16910542b4f1"
    private val workId = "e6bddba9-e98b-49cb-8bed-dce91d400520"
    private val url = "https://collect.example.org/v1/projects/$projectId"

    @Test
    fun `reads the exact project URLs with existing ODK credentials and no redirects`() {
        val (provider, _) = settings()
        val requested = mutableListOf<URL>()
        val connections = mutableListOf<HttpURLConnection>()
        val snapshot = RecordWorkReadOnlyClient(provider) { destination ->
            requested += destination
            val response = if (requested.size == 1) recordsJson() else workJson()
            fakeConnection(200, response).also { connections += it }
        }.load(projectId)

        assertEquals(1, snapshot.records.size)
        assertEquals(1, snapshot.pendingWork.size)
        assertEquals(RecordWorkType.FOLLOW_UP, snapshot.pendingWork.single().type)
        assertEquals(
            listOf("$url/records", "$url/record-work-items"),
            requested.map { it.toString() }
        )
        requested.forEach { assertEquals("https", it.protocol) }
        connections.forEach { connection ->
            verify(connection).setInstanceFollowRedirects(false)
            verify(connection).setUseCaches(false)
            verify(connection).setRequestProperty("Accept", "application/json")
            verify(connection).setRequestProperty("Cache-Control", "no-store")
            verify(connection).setRequestProperty(
                org.mockito.ArgumentMatchers.eq("Authorization"), startsWith("Basic ")
            )
            verify(connection).disconnect()
        }
    }

    @Test
    fun `missing credentials fail without a network request`() {
        val (provider, settings) = settings()
        whenever(settings.getString(ProjectKeys.KEY_PASSWORD)).thenReturn("")
        val client = RecordWorkReadOnlyClient(provider) {
            fail("Network request must not occur without credentials")
            throw IllegalStateException()
        }
        assertReason(ReadOnlyRequestFailure.NO_CREDENTIALS) { client.load(projectId) }
    }

    @Test
    fun `unscoped project URLs fail before a network request`() {
        val (provider, settings) = settings()
        whenever(settings.getString(ProjectKeys.KEY_SERVER_URL))
            .thenReturn("https://collect.example.org/other-path")
        val client = RecordWorkReadOnlyClient(provider) {
            fail("Invalid project URL must not reach network")
            throw IllegalStateException()
        }
        assertReason(ReadOnlyRequestFailure.INVALID_PROJECT) { client.load(projectId) }
    }

    @Test
    fun `status denials and redirects fail closed`() {
        val (provider, _) = settings()
        listOf(
            401 to ReadOnlyRequestFailure.AUTH_REQUIRED,
            403 to ReadOnlyRequestFailure.ACCESS_DENIED,
            302 to ReadOnlyRequestFailure.REDIRECT_REFUSED,
            503 to ReadOnlyRequestFailure.SERVER_ERROR
        ).forEach { (code, reason) ->
            val client = RecordWorkReadOnlyClient(provider) {
                fakeConnection(code, "[]")
            }
            assertReason(reason) { client.load(projectId) }
        }
    }

    @Test
    fun `malformed or oversized JSON is refused`() {
        val (provider, _) = settings()
        val badJson = RecordWorkReadOnlyClient(provider) {
            fakeConnection(200, "{ malformed }")
        }
        assertReason(ReadOnlyRequestFailure.INVALID_RESPONSE) { badJson.load(projectId) }
        val tooLarge = RecordWorkReadOnlyClient(provider) {
            fakeConnection(200, " ".repeat(1_048_577))
        }
        assertReason(ReadOnlyRequestFailure.INVALID_RESPONSE) { tooLarge.load(projectId) }
    }

    @Test
    fun `parser distinguishes pending follow ups from completed work`() {
        assertEquals(1, RecordWorkJsonParser.pending(workJson()).size)
        assertRejects { RecordWorkJsonParser.pending(workJson().replace("READY", "COMPLETED")) }
        assertRejects { RecordWorkJsonParser.pending(workJson().replace("2027-Q1-visit-2", "")) }
    }

    private fun recordsJson() = """[{
        "submissionId":"$submissionId",
        "instanceId":"uuid:9e1e9e90-5cc8-4e65-a5d0-acb5bdc1500d",
        "formId":"$submissionId","formVersionId":"$targetId",
        "submittedAt":"2026-11-20T11:00:00Z"
    }]"""

    private fun workJson() = """[{
        "id":"$workId","sourceSubmissionId":"$submissionId",
        "targetFormVersionId":"$targetId","workType":"FOLLOW_UP",
        "state":"READY","visitKey":"2027-Q1-visit-2"
    }]"""

    private fun fakeConnection(code: Int, text: String): HttpURLConnection =
        mock<HttpURLConnection>().also { c ->
            whenever(c.responseCode).thenReturn(code)
            whenever(c.contentType).thenReturn("application/json")
            whenever(c.inputStream).thenReturn(
                ByteArrayInputStream(text.toByteArray(StandardCharsets.UTF_8))
            )
        }

    private fun settings(): Pair<SettingsProvider, Settings> {
        val provider = mock<SettingsProvider>()
        val values = mock<Settings>()
        whenever(provider.getUnprotectedSettings(projectId)).thenReturn(values)
        whenever(values.getString(ProjectKeys.KEY_SERVER_URL)).thenReturn(url)
        whenever(values.getString(ProjectKeys.KEY_USERNAME)).thenReturn("enumerator")
        whenever(values.getString(ProjectKeys.KEY_PASSWORD)).thenReturn("demo_password")
        return provider to values
    }

    private fun assertReason(reason: ReadOnlyRequestFailure, block: () -> Unit) {
        try {
            block()
            fail("Expected $reason")
        } catch (ex: ReadOnlyRecordException) {
            assertEquals(reason, ex.reason)
        }
    }

    private fun assertRejects(block: () -> Unit) {
        try {
            block()
            fail("Expected validation failure")
        } catch (_: IllegalArgumentException) { }
    }
}
