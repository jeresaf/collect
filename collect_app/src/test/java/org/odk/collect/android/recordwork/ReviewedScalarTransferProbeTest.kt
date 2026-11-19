package org.odk.collect.android.recordwork

import org.javarosa.model.xform.XFormsModule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** Synthetic ODK XForms; actual JavaRosa models, zero uploaded record data. */
class ReviewedScalarTransferProbeTest {
    private val work = "e6bddba9-e98b-49cb-8bed-dce91d400520"
    private val source = "a8c07f51-b4c1-40a4-b31c-956d2ca5e048"
    private val target = "5a6f0bf6-795c-4d91-9861-16910542b4f1"
    private val instance = "uuid:9e1e9e90-5cc8-4e65-a5d0-acb5bdc1500d"
    private val privateAnswer = "PRIVATE_ANSWER_NEVER_REPORT"
    private val originalXml = """
        <data id="household" version="1">
          <name>$privateAnswer</name><count>27</count>
          <meta><instanceID>$instance</instanceID></meta>
        </data>
    """.trimIndent()

    private fun form(version: String, extra: String = "") = """
        <h:html xmlns:h="http://www.w3.org/1999/xhtml"
                xmlns="http://www.w3.org/2002/xforms">
          <h:head>
            <h:title>Household synthetic version $version</h:title>
            <model>
              <instance>
                <data id="household" version="$version">
                  <name/><count/>$extra<meta><instanceID/></meta>
                </data>
              </instance>
              <bind nodeset="/data/name" type="string"/>
              <bind nodeset="/data/count" type="int"/>
            </model>
          </h:head>
          <h:body>
            <input ref="/data/name"><label>Name</label></input>
            <input ref="/data/count"><label>Count</label></input>
          </h:body>
        </h:html>
    """.trimIndent()

    private val original = form("1")
    private val current = form("2")
    private lateinit var scratch: File

    @Before fun setup() {
        XFormsModule().registerModule()
        scratch = Files.createTempDirectory("record-work-native-transfer-").toFile()
    }

    @Test fun approvedTextIsNativeTransferredOnlyIntoDisposableTarget() {
        val p = plan()
        val snapshot = review(p, copyName = true)
        val result = probe().validate(p, snapshot, originalXml, sha(originalXml),
            instance, original, current)
        assertEquals(2, result.pathsReviewed)
        assertEquals(1, result.pathsTransferred)
        assertEquals(1, result.pathsToReenter)
        assertEquals(0, result.pathsLeftEmpty)
        assertEquals(p.targetXFormSha256, result.targetXFormSha256)
        assertEquals(instance, result.sourceInstanceId)
        assertFalse(result.toString().contains(privateAnswer))
        assertFalse(result.canSaveDraft)
        assertFalse(result.canSubmit)
        assertTrue(scratch.listFiles().orEmpty().isEmpty())
        scratch.deleteRecursively()
    }

    @Test fun emptyReviewAndMissingDecisionsBlockAllTransfers() {
        val p = plan()
        val incomplete = HistoricalMappingReview(p).apply {
            decide("/data/name", MappingDecision.REQUEST_REVIEWED_TRANSFER)
        }.snapshot()
        reject { probe().validate(p, incomplete, originalXml, sha(originalXml),
            instance, original, current) }
        assertTrue(scratch.listFiles().orEmpty().isEmpty())
        scratch.deleteRecursively()
    }

    @Test fun numericPathsCannotBeAutoTransferredWithoutTypedMigration() {
        val p = plan()
        val r = HistoricalMappingReview(p).apply {
            decide("/data/name", MappingDecision.REENTER)
            decide("/data/count", MappingDecision.REQUEST_REVIEWED_TRANSFER)
        }.snapshot()
        reject { probe().validate(p, r, originalXml, sha(originalXml),
            instance, original, current) }
        assertTrue(scratch.listFiles().orEmpty().isEmpty())
        scratch.deleteRecursively()
    }

    @Test fun changedSourceAnswerInvalidatesEarlierReviewedHash() {
        val p = plan()
        val r = review(p, true)
        val changed = originalXml.replace(privateAnswer, "UNREVIEWED_ANSWER")
        reject { probe().validate(p, r, changed, sha(originalXml),
            instance, original, current) }
        assertTrue(scratch.listFiles().orEmpty().isEmpty())
        scratch.deleteRecursively()
    }

    @Test fun forgedOrDifferentSourceInstanceIdentityIsRejected() {
        val p = plan()
        val r = review(p, true)
        reject { probe().validate(p, r, originalXml, sha(originalXml),
            "uuid:aaaaaaaa-aaaa-4aaa-aaaa-aaaaaaaaaaaa", original, current) }
        assertTrue(scratch.listFiles().orEmpty().isEmpty())
        scratch.deleteRecursively()
    }

    @Test fun changedTargetVersionInvalidatesApproval() {
        val p = plan()
        val r = review(p, true)
        val changedTarget = current.replace("synthetic version 2", "tampered version 2")
        reject { probe().validate(p, r, originalXml, sha(originalXml),
            instance, original, changedTarget) }
        assertTrue(scratch.listFiles().orEmpty().isEmpty())
        scratch.deleteRecursively()
    }

    @Test fun missingHistoricalSchemaRefusesCopy() {
        val p = plan(historical = null)
        val review = HistoricalMappingReview(p).apply {
            p.candidates.forEach { decide(it.targetPath, MappingDecision.REENTER) }
        }.snapshot()
        reject { probe().validate(p, review, originalXml, sha(originalXml),
            instance, null, current) }
        scratch.deleteRecursively()
    }

    @Test fun independentFollowupRefusesSourceAnswerTransfer() {
        val p = plan(type = RecordWorkType.FOLLOW_UP)
        val review = HistoricalMappingReview(p).apply {
            p.candidates.forEach { decide(it.targetPath, MappingDecision.REENTER) }
        }.snapshot()
        reject { probe().validate(p, review, originalXml, sha(originalXml),
            instance, original, current) }
        scratch.deleteRecursively()
    }

    @Test fun originalAnswersAreNeverReturnedOrLeftInCache() {
        val p = plan()
        val result = probe().validate(p, review(p, true), originalXml,
            sha(originalXml), instance, original, current)
        assertFalse(result.toString().contains(privateAnswer))
        assertTrue(scratch.listFiles().orEmpty().isEmpty())
        reject { result.copy(canSaveDraft = true) }
        reject { result.copy(canSubmit = true) }
        scratch.deleteRecursively()
    }

    private fun probe() = ReviewedScalarTransferProbe(scratch)

    private fun plan(
        historical: String? = original,
        type: RecordWorkType = RecordWorkType.AMENDMENT
    ): HistoricalMappingPlan = HistoricalSchemaMappingEngine.plan(
        work, source, target, type, originalXml,
        historical, current, "household", "2"
    )

    private fun review(
        p: HistoricalMappingPlan,
        copyName: Boolean
    ): MappingReviewSnapshot = HistoricalMappingReview(p).apply {
        p.candidates.forEach {
            decide(it.targetPath,
                if (copyName && it.targetPath == "/data/name")
                    MappingDecision.REQUEST_REVIEWED_TRANSFER
                else MappingDecision.REENTER)
        }
    }.snapshot()

    private fun sha(value: String) = ReviewedScalarTransferProbe.sha256(value)

    private fun reject(block: () -> Unit) {
        var rejected = false
        try { block() } catch (_: Exception) { rejected = true }
        assertTrue("Unreviewed/unsafe transfer must be denied", rejected)
    }
}
