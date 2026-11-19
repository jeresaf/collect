package org.odk.collect.android.recordwork

import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
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
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class RecordWorkReadOnlyClientTest {
    private val projectId = "c77a446e-18b6-4a5a-9f52-aa12fedba010"
    private val submissionId = "a8c07f51-b4c1-40a4-b31c-956d2ca5e048"
    private val targetId = "5a6f0bf6-795c-4d91-9861-16910542b4f1"
    private val workId = "e6bddba9-e98b-49cb-8bed-dce91d400520"
    private val url = "https://collect.example.org/v1/projects/$projectId"

    @Test
    fun `uses project credentials on just the two scoped endpoints`() {
        val seen = mutableListOf<Request>()
        val (provider, _) = settings()
        val snapshot = RecordWorkReadOnlyClient(provider, fakeHttp { request ->
            seen.add(request)
            if (request.url.encodedPath.endsWith("/record-work-items")) {
                response(request, 200, """[{
                    "id":"$workId", "sourceSubmissionId":"$submissionId",
                    "targetFormVersionId":"$targetId","workType":"FOLLOW_UP",
                    "state":"READY","visitKey":"2027-Q1-visit-2"
                }]""")
            } else {
                response(request, 200, """[{
                    "submissionId":"$submissionId",
                    "instanceId":"uuid:9e1e9e90-5cc8-4e65-a5d0-acb5bdc1500d",
                    "formId":"$submissionId", "formVersionId":"$targetId",
                    "submittedAt":"2026-11-20T11:00:00Z"
                }]""")
            }
        }).load(projectId)

        assertEquals(1, snapshot.records.size)
        assertEquals(1, snapshot.pendingWork.size)
        assertEquals(RecordWorkType.FOLLOW_UP, snapshot.pendingWork.single().type)
        assertEquals(
            listOf("$url/records", "$url/record-work-items"),
            seen.map { it.url.toString() }
        )
        seen.forEach {
            assertTrue(it.header("Authorization")!!.startsWith("Basic "))
            assertEquals("application/json", it.header("Accept"))
            assertEquals("no-store", it.header("Cache-Control"))
            assertEquals("https", it.url.scheme)
        }
    }

    @Test
    fun `missing credentials fail without making a network request`() {
        val (provider, settings) = settings()
        whenever(settings.getString(ProjectKeys.KEY_PASSWORD)).thenReturn("")
        val client = RecordWorkReadOnlyClient(provider, fakeHttp {
            fail("Network request must not occur without credentials")
            throw IllegalStateException()
        })
        assertReason(ReadOnlyRequestFailure.NO_CREDENTIALS) { client.load(projectId) }
    }

    @Test
    fun `rejects unscoped server configuration`() {
        val (provider, settings) = settings()
        whenever(settings.getString(ProjectKeys.KEY_SERVER_URL))
            .thenReturn("https://collect.example.org/other-path")
        val client = RecordWorkReadOnlyClient(provider, fakeHttp {
            fail("Invalid project URL must not reach network")
            throw IllegalStateException()
        })
        assertReason(ReadOnlyRequestFailure.INVALID_PROJECT) { client.load(projectId) }
    }

    @Test
    fun `401 403 and redirects never expose a cached record list`() {
        val (provider, _) = settings()
        listOf(
            401 to ReadOnlyRequestFailure.AUTH_REQUIRED,
            403 to ReadOnlyRequestFailure.ACCESS_DENIED,
            302 to ReadOnlyRequestFailure.REDIRECT_REFUSED,
            503 to ReadOnlyRequestFailure.SERVER_ERROR
        ).forEach { (code, reason) ->
            val client = RecordWorkReadOnlyClient(provider, fakeHttp { req ->
                response(req, code, "[]")
            })
            assertReason(reason) { client.load(projectId) }
        }
    }

    @Test
    fun `rejects malformed and overlength record bodies`() {
        val (provider, _) = settings()
        val badJson = RecordWorkReadOnlyClient(provider, fakeHttp { req ->
            response(req, 200, "{ malformed json }")
        })
        assertReason(ReadOnlyRequestFailure.INVALID_RESPONSE) { badJson.load(projectId) }

        val tooLarge = RecordWorkReadOnlyClient(provider, fakeHttp { req ->
            response(req, 200, " ".repeat(1_048_577))
        })
        assertReason(ReadOnlyRequestFailure.INVALID_RESPONSE) { tooLarge.load(projectId) }
    }

    @Test
    fun `parser rejects completed work and requires visitKey on follow ups`() {
        val ready = """[{
            "id":"$workId", "sourceSubmissionId":"$submissionId",
            "targetFormVersionId":"$targetId", "workType":"FOLLOW_UP",
            "state":"READY", "visitKey":"2027-Q1-visit-2"
        }]"""
        assertEquals(1, RecordWorkJsonParser.pending(ready).size)
        try {
            RecordWorkJsonParser.pending(ready.replace("\"READY\"", "\"COMPLETED\""))
            fail("Completed work must not be presented as pending")
        } catch (_: IllegalArgumentException) { }

        try {
            RecordWorkJsonParser.pending(ready.replace("2027-Q1-visit-2", ""))
            fail("Follow-up visit without key must fail")
        } catch (_: IllegalArgumentException) { }
    }

    private fun settings(): Pair<SettingsProvider, Settings> {
        val provider = mock<SettingsProvider>()
        val settings = mock<Settings>()
        whenever(provider.getUnprotectedSettings(projectId)).thenReturn(settings)
        whenever(settings.getString(ProjectKeys.KEY_SERVER_URL)).thenReturn(url)
        whenever(settings.getString(ProjectKeys.KEY_USERNAME)).thenReturn("enumerator")
        whenever(settings.getString(ProjectKeys.KEY_PASSWORD)).thenReturn("demo_password")
        return provider to settings
    }

    private fun fakeHttp(responder: (Request) -> Response): OkHttpClient =
        OkHttpClient.Builder()
            .followRedirects(false)
            .followSslRedirects(false)
            .cache(null)
            .addInterceptor(Interceptor { chain -> responder(chain.request()) })
            .build()

    private fun response(request: Request, code: Int, text: String) =
        Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message("Synthetic test")
            .body(text.toResponseBody("application/json".toMediaType()))
            .build()

    private fun assertReason(expected: ReadOnlyRequestFailure, execute: () -> Unit) {
        try {
            execute()
            fail("Expected $expected")
        } catch (ex: ReadOnlyRecordException) {
            assertEquals(expected, ex.reason)
        }
    }
}
