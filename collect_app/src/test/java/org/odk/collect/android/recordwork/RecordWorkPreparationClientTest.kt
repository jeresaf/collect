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
            prepareJson(required = true).replace("<h:html/>", "")
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
    fun `assessed prepare reports changes without disclosing raw answers`() {
        val (settings, _) = projectSettings()
        val client = RecordWorkPreparationClient(settings) {
            connection(200, validAssessedPreparation()).first
        }
        val preview = client.prepare(
            project, workId, source, target, RecordWorkType.AMENDMENT,
            assessCrossVersion = true
        )
        val assessment = requireNotNull(preview.assessment)
        assertEquals(workId, assessment.workId)
        assertEquals(target, assessment.targetFormVersionId)
        assertEquals("2", assessment.targetVersion)
        assertFalse(assessment.canEdit)
        assertFalse(assessment.canSubmit)
        assertFalse(preview.toString().contains("SENSITIVE_PERSONAL_VALUE"))
    }

    @Test
    fun historicalPrepareBindsTwoExactDefinitionsAndDoesNotCopyAnswers() {
        val (settings, _) = projectSettings()
        val sourceForm = """
            <h:html xmlns:h="http://www.w3.org/1999/xhtml"
                    xmlns="http://www.w3.org/2002/xforms">
              <h:head><h:title>Original household form</h:title>
              <model><instance>
                <data id="household" version="1"><name/></data>
              </instance><bind nodeset="/data/name" type="string"/></model></h:head>
              <h:body><input ref="/data/name"><label>Name</label></input></h:body>
            </h:html>
        """.trimIndent()
        val response = org.json.JSONObject(validAssessedPreparation())
            .put("sourceXFormXml", sourceForm)
            .toString()
        val client = RecordWorkPreparationClient(settings) {
            connection(200, response).first
        }
        val preview = client.prepare(
            project, workId, source, target, RecordWorkType.AMENDMENT,
            assessCrossVersion = true
        )
        val plan = requireNotNull(preview.mappingPlan)
        assertEquals("1", plan.sourceVersion)
        assertEquals("2", plan.targetVersion)
        assertTrue(plan.sourceXFormSha256 != null)
        assertFalse(plan.canPrefill)
        assertFalse(plan.canEdit)
        assertFalse(plan.canSubmit)
        assertFalse(plan.toString().contains("SENSITIVE_PERSONAL_VALUE"))
    }

    @Test
    fun prepareWithoutLegacySourceDefinitionStillBlocksTransfers() {
        val (settings, _) = projectSettings()
        val client = RecordWorkPreparationClient(settings) {
            connection(200, validAssessedPreparation()).first
        }
        val preview = client.prepare(
            project, workId, source, target, RecordWorkType.AMENDMENT,
            assessCrossVersion = true
        )
        val plan = requireNotNull(preview.mappingPlan)
        assertTrue(MappingRisk.NO_HISTORICAL_SCHEMA in plan.globalRisks)
        assertTrue(plan.candidates.none { it.safeScalarProposal })
        assertFalse(plan.canEdit)
    }

    @Test
    fun nativeStagingIsBoundToAuthenticatedPreparedTarget() {
        val (settings, _) = projectSettings()
        var invoked = 0
        val client = RecordWorkPreparationClient(
            settings,
            nativeProbe = { plan, targetXml, historicalXml ->
                invoked++
                assertEquals(target, plan.targetFormVersionId)
                assertTrue(targetXml.contains("version=\"2\""))
                assertEquals(null, historicalXml)
                NativeStagingEvidence(
                    workId = plan.workId,
                    sourceSubmissionId = plan.sourceSubmissionId,
                    targetFormVersionId = plan.targetFormVersionId,
                    workType = plan.workType,
                    sourceXFormSha256 = plan.sourceXFormSha256,
                    targetXFormSha256 = plan.targetXFormSha256,
                    targetJavaRosaInitialized = true,
                    historicalJavaRosaInitialized = false,
                    compatibleScalarCandidates = 0,
                    operatorPathsReviewed = false
                )
            }
        ) { connection(200, validAssessedPreparation()).first }

        val preview = client.prepare(project, workId, source, target,
            RecordWorkType.AMENDMENT, assessCrossVersion = true)
        assertEquals(1, invoked)
        assertTrue(requireNotNull(preview.nativeStaging).targetJavaRosaInitialized)
        assertFalse(requireNotNull(preview.nativeStaging).canCreateDraft)
        assertFalse(requireNotNull(preview.nativeStaging).canSubmit)
    }

    @Test
    fun nativeEvidenceForDifferentFormVersionIsRejected() {
        val (settings, _) = projectSettings()
        val client = RecordWorkPreparationClient(
            settings,
            nativeProbe = { plan, _, _ ->
                NativeStagingEvidence(
                    workId = plan.workId,
                    sourceSubmissionId = plan.sourceSubmissionId,
                    targetFormVersionId = source,
                    workType = plan.workType,
                    sourceXFormSha256 = plan.sourceXFormSha256,
                    targetXFormSha256 = plan.targetXFormSha256,
                    targetJavaRosaInitialized = true,
                    historicalJavaRosaInitialized = false,
                    compatibleScalarCandidates = 0,
                    operatorPathsReviewed = false
                )
            }
        ) { connection(200, validAssessedPreparation()).first }
        assertError(PreparationError.BAD_RESPONSE) {
            client.prepare(project, workId, source, target,
                RecordWorkType.AMENDMENT, assessCrossVersion = true)
        }
    }

    @Test
    fun freshServerPrepareRevalidatesSchemaHashAndWorkBeforeReviewedTransfer() {
        val (settings, _) = projectSettings()
        val response = verifiedTransferResponse()
        var validatorCalls = 0
        val urls = mutableListOf<String>()
        val client = RecordWorkPreparationClient(
            settings,
            transferProbe = { plan, review, sourceXml, sourceSha, sourceInstanceId, _, _ ->
                validatorCalls++
                assertEquals(workId, plan.workId)
                assertEquals("uuid:9e1e9e90-5cc8-4e65-a5d0-acb5bdc1500d", sourceInstanceId)
                assertEquals(ReviewedScalarTransferProbe.sha256(sourceXml), sourceSha)
                assertTrue(review.reviewedEveryTargetPath)
                ReviewedTransferEvidence(
                    plan.workId, plan.sourceSubmissionId, plan.targetFormVersionId,
                    plan.workType, sourceInstanceId, sourceSha,
                    requireNotNull(plan.sourceXFormSha256), plan.targetXFormSha256,
                    review.decisions.size, 1, 0, 0
                )
            }
        ) { url ->
            urls += url.toString()
            connection(200, response).first
        }
        val prepared = client.prepare(
            project, workId, source, target, RecordWorkType.AMENDMENT,
            assessCrossVersion = true
        )
        val plan = requireNotNull(prepared.mappingPlan)
        val review = HistoricalMappingReview(plan).apply {
            plan.candidates.forEach {
                decide(it.targetPath, MappingDecision.REQUEST_REVIEWED_TRANSFER)
            }
        }.snapshot()
        val result = client.validateReviewedTransfer(project, prepared, review)
        assertEquals(1, validatorCalls)
        assertEquals(1, result.pathsTransferred)
        assertFalse(result.canSaveDraft)
        assertFalse(result.canSubmit)
        assertEquals(2, urls.size)
        assertTrue(urls.all { it == "$base/record-work-items/$workId/prepare" })
    }

    @Test
    fun changedAnswersBetweenPreviewAndTransferBlockNativeInvocation() {
        val (settings, _) = projectSettings()
        var requests = 0
        var called = 0
        val client = RecordWorkPreparationClient(
            settings,
            transferProbe = { _, _, _, _, _, _, _ ->
                called++
                error("Validator must never be called after source changes")
            }
        ) {
            requests++
            val response = if (requests == 1) verifiedTransferResponse()
                else org.json.JSONObject(verifiedTransferResponse()).put(
                    "sourceXml", org.json.JSONObject(verifiedTransferResponse())
                        .getString("sourceXml").replace("SENSITIVE_PERSONAL_VALUE", "changed")
                ).toString()
            connection(200, response).first
        }
        val prepared = client.prepare(
            project, workId, source, target, RecordWorkType.AMENDMENT,
            assessCrossVersion = true
        )
        val plan = requireNotNull(prepared.mappingPlan)
        val review = HistoricalMappingReview(plan).apply {
            plan.candidates.forEach {
                decide(it.targetPath, MappingDecision.REQUEST_REVIEWED_TRANSFER)
            }
        }.snapshot()
        assertError(PreparationError.BAD_RESPONSE) {
            client.validateReviewedTransfer(project, prepared, review)
        }
        assertEquals(0, called)
    }

    private fun verifiedTransferResponse(): String {
        fun formXml(version: String) = """
            <h:html xmlns:h="http://www.w3.org/1999/xhtml"
                    xmlns="http://www.w3.org/2002/xforms">
              <h:head><h:title>Synthetic Household</h:title>
                <model><instance><data id="household" version="$version"><name/></data></instance>
                  <bind nodeset="/data/name" type="string"/>
                </model>
              </h:head><h:body>
                <input ref="/data/name"><label>Name</label></input>
              </h:body>
            </h:html>
        """.trimIndent()
        val response = org.json.JSONObject(prepareJson(required = true))
        response.getJSONObject("work").put(
            "sourceInstanceId", "uuid:9e1e9e90-5cc8-4e65-a5d0-acb5bdc1500d"
        )
        return response.put(
            "sourceXml",
            """<data id="household" version="1">
              <name>SENSITIVE_PERSONAL_VALUE</name>
              <meta><instanceID>uuid:9e1e9e90-5cc8-4e65-a5d0-acb5bdc1500d</instanceID></meta>
            </data>"""
        ).put("sourceXFormXml", formXml("1"))
            .put("targetXFormXml", formXml("2"))
            .toString()
    }

    @Test
    fun followupInertBindingRefetchesReadyWorkAndNeverCopiesSourceAnswers() {
        val (settings, _) = projectSettings()
        val storage = QuarantinedWorkDraftVault(
            java.nio.file.Files.createTempDirectory("recordwork-client-stage-").toFile()
        )
        var requestCount = 0
        val response = org.json.JSONObject(validAssessedPreparation()).apply {
            getJSONObject("work").put("workType", "FOLLOW_UP")
                .put("sourceInstanceId", "uuid:9e1e9e90-5cc8-4e65-a5d0-acb5bdc1500d")
                .put("visitKey", "household-visit-2")
        }.toString()
        val client = RecordWorkPreparationClient(
            settings,
            nativeProbe = { plan, _, historical ->
                NativeStagingEvidence(
                    plan.workId, plan.sourceSubmissionId, plan.targetFormVersionId,
                    plan.workType, plan.sourceXFormSha256, plan.targetXFormSha256,
                    true, historical != null, 0, false
                )
            }
        ) {
            requestCount++
            connection(200, response).first
        }
        val preview = client.prepare(
            project, workId, source, target, RecordWorkType.FOLLOW_UP,
            assessCrossVersion = true
        )
        val plan = requireNotNull(preview.mappingPlan)
        val review = HistoricalMappingReview(plan).apply {
            plan.candidates.forEach {
                decide(it.targetPath, MappingDecision.REENTER)
            }
        }.snapshot()
        val saved = client.stageQuarantinedWork(project, preview, review, storage)
        assertEquals(2, requestCount)
        assertEquals(RecordWorkType.FOLLOW_UP, saved.workType)
        assertEquals("household-visit-2", saved.visitKey)
        assertEquals(0, saved.validationTransfers)
        assertFalse(saved.canOpenEditor)
        assertFalse(saved.canUpload)
    }

    @Test
    fun changedSourceBetweenPreviewAndStageIsRejectedBeforeAnyWorkBinding() {
        val (settings, _) = projectSettings()
        val storageRoot = java.nio.file.Files.createTempDirectory(
            "recordwork-stage-drift-"
        ).toFile()
        val storage = QuarantinedWorkDraftVault(storageRoot)
        var requestCount = 0
        fun response(newAnswer: Boolean) = org.json.JSONObject(
            validAssessedPreparation()
        ).apply {
            getJSONObject("work").put("workType", "FOLLOW_UP")
                .put("sourceInstanceId", "uuid:9e1e9e90-5cc8-4e65-a5d0-acb5bdc1500d")
                .put("visitKey", "household-visit-2")
            if (newAnswer) put("sourceXml",
                getString("sourceXml").replace("SENSITIVE_PERSONAL_VALUE",
                    "NEW_UNREVIEWED_VALUE"))
        }.toString()
        val client = RecordWorkPreparationClient(
            settings,
            nativeProbe = { plan, _, _ ->
                NativeStagingEvidence(
                    plan.workId, plan.sourceSubmissionId, plan.targetFormVersionId,
                    plan.workType, plan.sourceXFormSha256, plan.targetXFormSha256,
                    true, false, 0, false
                )
            }
        ) {
            requestCount++
            connection(200, response(requestCount > 1)).first
        }
        val preview = client.prepare(
            project, workId, source, target, RecordWorkType.FOLLOW_UP,
            assessCrossVersion = true
        )
        val plan = requireNotNull(preview.mappingPlan)
        val review = HistoricalMappingReview(plan).apply {
            plan.candidates.forEach {
                decide(it.targetPath, MappingDecision.REENTER)
            }
        }.snapshot()
        assertError(PreparationError.BAD_RESPONSE) {
            client.stageQuarantinedWork(project, preview, review, storage)
        }
        assertEquals(2, requestCount)
        assertFalse(java.io.File(storageRoot, "recordwork-drafts").exists())
    }

    @Test
    fun `assessed prepare fails on invalid target version and does not create a draft`() {
        val (settings, _) = projectSettings()
        val spoofed = org.json.JSONObject(validAssessedPreparation())
            .put("targetFormVersion", "999")
            .toString()
        val client = RecordWorkPreparationClient(settings) {
            connection(200, spoofed).first
        }
        assertError(PreparationError.BAD_RESPONSE) {
            client.prepare(
                project, workId, source, target, RecordWorkType.AMENDMENT,
                assessCrossVersion = true
            )
        }
    }

    private fun validAssessedPreparation(): String {
        val xform = """
            <h:html xmlns:h="http://www.w3.org/1999/xhtml"
                    xmlns="http://www.w3.org/2002/xforms">
              <h:head>
                <h:title>Synthetic Household Test</h:title>
                <model><instance>
                  <data id="household" version="2"><name/><newQuestion/></data>
                </instance></model>
              </h:head>
              <h:body><input ref="/data/name"><label>Name</label></input></h:body>
            </h:html>
        """.trimIndent()
        return org.json.JSONObject(prepareJson(required = true))
            .put("sourceXml",
                """<data id="household" version="1"><name>SENSITIVE_PERSONAL_VALUE</name></data>""")
            .put("targetXFormXml", xform)
            .toString()
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
