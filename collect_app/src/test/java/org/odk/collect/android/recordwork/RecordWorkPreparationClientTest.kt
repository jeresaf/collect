package org.odk.collect.android.recordwork

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers
import org.mockito.Mockito.verify
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.odk.collect.settings.SettingsProvider
import org.odk.collect.settings.keys.ProjectKeys
import org.odk.collect.shared.settings.Settings
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RecordWorkPreparationClientTest {
    private val project = "local-odk-project"
    private val serverProject = "c77a446e-18b6-4a5a-9f52-aa12fedba010"
    private val source = "a8c07f51-b4c1-40a4-b31c-956d2ca5e048"
    private val form = "d4c30147-7e17-42a2-89be-5b9f03aba039"
    private val target = "5a6f0bf6-795c-4d91-9861-16910542b4f1"
    private val workId = "e6bddba9-e98b-49cb-8bed-dce91d400520"
    private val base = "https://collect.example.org/v1/projects/$serverProject"

    @Test
    fun `eligible target versions are scoped by source and workflow`() {
        val (settings, _) = projectSettings()
        val urls = mutableListOf<String>()
        val client = RecordWorkPreparationClient(settings) { url ->
            urls.add(url.toString())
            connection(200, catalogJson(sameForm = true)).first
        }
        val candidates = client.targets(project, source, RecordWorkType.AMENDMENT)
        assertEquals(1, candidates.size)
        assertEquals(target, candidates.single().targetFormVersionId)
        assertEquals(listOf("$base/records/$source/target-versions?workType=AMENDMENT"), urls)
        assertTrue(candidates.single().sameFormAsSource)
    }

    @Test
    fun `rejects cross-form amendments and permits assigned followup targets`() {
        val (settings, _) = projectSettings()
        val client = RecordWorkPreparationClient(settings) {
            connection(200, catalogJson(sameForm = false)).first
        }
        assertError(PreparationError.BAD_RESPONSE) {
            client.targets(project, source, RecordWorkType.AMENDMENT)
        }
        assertFalse(client.targets(project, source, RecordWorkType.FOLLOW_UP)
            .single().sameFormAsSource)
    }

    @Test
    fun `create work uses immutable request ID and exact selected target`() {
        val (settings, _) = projectSettings()
        lateinit var uploaded: ByteArrayOutputStream
        lateinit var captured: HttpURLConnection
        val client = RecordWorkPreparationClient(settings) {
            val (http, output) = connection(201, workJson())
            uploaded = output
            captured = http
            http
        }
        val work = client.create(
            project,
            NewRecordWork(source, target, RecordWorkType.FOLLOW_UP,
                "m4b2-client-001", "2027-Q1-visit-2")
        )
        assertEquals(workId, work.id)
        assertEquals(target, work.targetFormVersionId)
        val json = org.json.JSONObject(uploaded.toString(StandardCharsets.UTF_8.name()))
        assertEquals("FOLLOW_UP", json.getString("workType"))
        assertEquals("m4b2-client-001", json.getString("clientRequestId"))
        assertEquals("2027-Q1-visit-2", json.getString("visitKey"))
        assertFalse(json.has("deprecatedID"))
        verify(captured).setInstanceFollowRedirects(false)
        verify(captured).setUseCaches(false)
        verify(captured).setRequestProperty(
            ArgumentMatchers.eq("Authorization"), ArgumentMatchers.startsWith("Basic ")
        )
        verify(captured).disconnect()
    }

    @Test
    fun `prepare returns only metadata after validating required mapping and work binding`() {
        val (settings, _) = projectSettings()
        val client = RecordWorkPreparationClient(settings) {
            connection(200, prepareJson(required = true)).first
        }
        val summary = client.prepare(
            project, workId, source, target, RecordWorkType.AMENDMENT
        )
        assertEquals("2", summary.targetFormVersion)
        assertEquals(workId, summary.workId)
        assertTrue(summary.mappingApprovalRequired)
        assertFalse(summary.toString().contains("sensitive"))
    }

    @Test
    fun `prepare refuses nonmatching work, disabled approval, and omitted target xml`() {
        val (settings, _) = projectSettings()
        for (body in listOf(
            prepareJson(required = false),
            prepareJson(required = true).replace(target, source),
            prepareJson(required = true).replace("targetXFormXml\\\":\\\"<h:html/>",
                "targetXFormXml\\\":\\\"")
        )) {
            val client = RecordWorkPreparationClient(settings) {
                connection(200, body).first
            }
            assertError(PreparationError.BAD_RESPONSE) {
                client.prepare(project, workId, source, target, RecordWorkType.AMENDMENT)
            }
        }
    }

    @Test
    fun `401 403 404 409 and redirects are never accepted`() {
        val (settings, _) = projectSettings()
        listOf(
            401 to PreparationError.LOGIN_REQUIRED,
            403 to PreparationError.ACCESS_DENIED,
            404 to PreparationError.NOT_FOUND,
            409 to PreparationError.CONFLICT,
            302 to PreparationError.REDIRECT_BLOCKED
        ).forEach { (code, expected) ->
            val client = RecordWorkPreparationClient(settings) {
                connection(code, "[]").first
            }
            assertError(expected) {
                client.targets(project, source, RecordWorkType.FOLLOW_UP)
            }
        }
    }

    private fun projectSettings(): Pair<SettingsProvider, Settings> {
        val provider = mock<SettingsProvider>()
        val values = mock<Settings>()
        whenever(provider.getUnprotectedSettings(project)).thenReturn(values)
        whenever(values.getString(ProjectKeys.KEY_SERVER_URL)).thenReturn(base)
        whenever(values.getString(ProjectKeys.KEY_USERNAME)).thenReturn("enumerator")
        whenever(values.getString(ProjectKeys.KEY_PASSWORD)).thenReturn("synthetic-only")
        return provider to values
    }

    private fun connection(code: Int, response: String): Pair<HttpURLConnection, ByteArrayOutputStream> {
        val http = mock<HttpURLConnection>()
        val output = ByteArrayOutputStream()
        whenever(http.responseCode).thenReturn(code)
        whenever(http.contentType).thenReturn("application/json")
        whenever(http.inputStream).thenReturn(
            ByteArrayInputStream(response.toByteArray(StandardCharsets.UTF_8))
        )
        whenever(http.outputStream).thenReturn(output)
        return http to output
    }

    private fun catalogJson(sameForm: Boolean) = """[{
        "targetFormVersionId":"$target",
        "formId":"$form",
        "formTitle":"Household survey",
        "version":"2",
        "latest":true,
        "sameFormAsSource":$sameForm
    }]"""

    private fun workJson() = """{
        "id":"$workId", "sourceSubmissionId":"$source",
        "targetFormVersionId":"$target","workType":"FOLLOW_UP",
        "state":"READY", "visitKey":"2027-Q1-visit-2"
    }"""

    private fun prepareJson(required: Boolean) = """{
        "work":{
            "id":"$workId","sourceSubmissionId":"$source",
            "targetFormVersionId":"$target","workType":"AMENDMENT","state":"READY"
        },
        "sourceXml":"<data>private sensitive source</data>",
        "targetXFormXml":"<h:html/>",
        "targetFormId":"household",
        "targetFormVersion":"2",
        "mappingApprovalRequired":$required
    }"""

    private fun assertError(reason: PreparationError, block: () -> Unit) {
        try {
            block()
            fail("Expected $reason")
        } catch (e: PreparationException) {
            assertEquals(reason, e.reason)
        }
    }
}
