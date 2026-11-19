package org.odk.collect.android.recordwork

import org.javarosa.model.xform.XFormsModule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Real JavaRosa FormDef creation and blank-form initialization, NOT mocks.
 * Synthetic data only; original answers never enter the staging directory.
 */
class JavaRosaStagingProbeTest {
    private val workId = "e6bddba9-e98b-49cb-8bed-dce91d400520"
    private val source = "a8c07f51-b4c1-40a4-b31c-956d2ca5e048"
    private val target = "5a6f0bf6-795c-4d91-9861-16910542b4f1"
    private val sensitive = "SENSITIVE_ANSWER_NO_DISK"
    private val submitted = """
        <data id="household" version="1">
          <name>$sensitive</name>
          <meta><instanceID>uuid:9e1e9e90-5cc8-4e65-a5d0-acb5bdc1500d</instanceID></meta>
        </data>
    """.trimIndent()

    private fun xform(version: String, id: String = "household") = """
        <h:html xmlns:h="http://www.w3.org/1999/xhtml"
                xmlns="http://www.w3.org/2002/xforms">
          <h:head>
            <h:title>Household Form</h:title>
            <model>
              <instance>
                <data id="$id" version="$version">
                  <name/><meta><instanceID/></meta>
                </data>
              </instance>
              <bind nodeset="/data/name" type="string"/>
            </model>
          </h:head>
          <h:body>
            <input ref="/data/name"><label>Name</label></input>
          </h:body>
        </h:html>
    """.trimIndent()

    private val original = xform("1")
    private val selected = xform("2")
    private lateinit var scratch: File

    @Before fun setup() {
        XFormsModule().registerModule()
        scratch = Files.createTempDirectory("recordwork-native-synthetic-").toFile()
    }

    @Test fun parsesAndInitializesBothExactNativeFormDefsWithoutSavingAnswers() {
        val p = plan()
        val native = JavaRosaStagingProbe(scratch).inspect(p, selected, original)
        assertTrue(native.targetJavaRosaInitialized)
        assertTrue(native.historicalJavaRosaInitialized)
        assertEquals(p.targetXFormSha256, native.targetXFormSha256)
        assertEquals(p.sourceXFormSha256, native.sourceXFormSha256)
        assertEquals(1, native.compatibleScalarCandidates)
        assertFalse(native.canImportAnswers)
        assertFalse(native.canCreateDraft)
        assertFalse(native.canSubmit)
        assertFalse(native.toString().contains(sensitive))
        assertTrue(scratch.listFiles().orEmpty().isEmpty())
        scratch.deleteRecursively()
    }

    @Test fun nativePreviewWorksForLegacyWithMissingHistoricalSchemaButAllowsNoAnswerCopy() {
        val p = plan(sourceXForm = null)
        val native = JavaRosaStagingProbe(scratch).inspect(p, selected, null)
        assertTrue(native.targetJavaRosaInitialized)
        assertFalse(native.historicalJavaRosaInitialized)
        assertEquals(0, native.compatibleScalarCandidates)
        assertFalse(native.canCreateDraft)
        scratch.deleteRecursively()
    }

    @Test fun changedTargetXmlFailsHashCheckBeforeNativeParsingOrStaging() {
        val p = plan()
        rejects {
            JavaRosaStagingProbe(scratch).inspect(
                p, selected.replace("Household Form", "Tampered Form"), original
            )
        }
        assertTrue(scratch.listFiles().orEmpty().isEmpty())
        scratch.deleteRecursively()
    }

    @Test fun changedHistoricXmlCannotBeUsedToTransferAnswers() {
        val p = plan()
        rejects {
            JavaRosaStagingProbe(scratch).inspect(
                p, selected, original.replace("version=\"1\"", "version=\"9\"")
            )
        }
        assertTrue(scratch.listFiles().orEmpty().isEmpty())
        scratch.deleteRecursively()
    }

    @Test fun workItemOrVersionChangeInvalidatesOperatorsReview() {
        val p = plan()
        val review = HistoricalMappingReview(p).apply {
            decide("/data/name", MappingDecision.REQUEST_REVIEWED_TRANSFER)
        }.snapshot()
        val good = JavaRosaStagingProbe(scratch).inspect(p, selected, original, review)
        assertTrue(good.operatorPathsReviewed)
        assertFalse(good.canImportAnswers)
        rejects {
            JavaRosaStagingProbe(scratch).inspect(
                p, selected, original,
                review.copy(workId = source)
            )
        }
        rejects {
            JavaRosaStagingProbe(scratch).inspect(
                p, selected, original,
                review.copy(targetXFormSha256 = "not-the-original-target")
            )
        }
        scratch.deleteRecursively()
    }

    @Test fun independentFollowupDoesNotAcquireCopyPermission() {
        val p = plan(type = RecordWorkType.FOLLOW_UP)
        val native = JavaRosaStagingProbe(scratch).inspect(p, selected, original)
        assertEquals(RecordWorkType.FOLLOW_UP, native.workType)
        assertEquals(0, native.compatibleScalarCandidates)
        assertFalse(native.canImportAnswers)
        assertFalse(native.canSubmit)
        scratch.deleteRecursively()
    }

    @Test fun allEvidencePermissionsStayStructurallyDenied() {
        val native = JavaRosaStagingProbe(scratch).inspect(plan(), selected, original)
        rejects { native.copy(canCreateDraft = true) }
        rejects { native.copy(canImportAnswers = true) }
        rejects { native.copy(canSubmit = true) }
        scratch.deleteRecursively()
    }

    @Test fun unsupportedExternalXFormSourceFailsClosedAndCleansScratch() {
        val external = selected.replace(
            "</instance>", "</instance><instance id=\"external\" src=\"https://evil.example/x.xml\"/>"
        )
        val p = plan(targetXForm = external)
        rejects { JavaRosaStagingProbe(scratch).inspect(p, external, original) }
        assertTrue(scratch.listFiles().orEmpty().isEmpty())
        scratch.deleteRecursively()
    }

    private fun plan(
        sourceXForm: String? = original,
        targetXForm: String = selected,
        type: RecordWorkType = RecordWorkType.AMENDMENT
    ): HistoricalMappingPlan = HistoricalSchemaMappingEngine.plan(
        workId = workId,
        sourceSubmissionId = source,
        targetFormVersionId = target,
        workType = type,
        sourceXml = submitted,
        sourceXFormXml = sourceXForm,
        targetXFormXml = targetXForm,
        targetFormId = "household",
        targetFormVersion = "2"
    )

    private fun rejects(block: () -> Unit) {
        var rejected = false
        try { block() } catch (_: Exception) { rejected = true }
        assertTrue("Unsafe native staging must be rejected", rejected)
    }
}
