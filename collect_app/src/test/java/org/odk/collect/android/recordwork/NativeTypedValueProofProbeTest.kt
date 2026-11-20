package org.odk.collect.android.recordwork

import org.javarosa.model.xform.XFormsModule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.UUID

/** Synthetic XML only; no original household records or imported answers. */
class NativeTypedValueProofProbeTest {
    @get:Rule val disk = TemporaryFolder()
    private val work = "e6bddba9-e98b-49cb-8bed-dce91d400520"
    private val source = "a8c07f51-b4c1-40a4-b31c-956d2ca5e048"
    private val target = "5a6f0bf6-795c-4d91-9861-16910542b4f1"
    private val instance = "uuid:9e1e9e90-5cc8-4e65-a5d0-acb5bdc1500d"

    @Before fun native() { XFormsModule().registerModule() }

    private fun form(version: String, type: String = "int") = """
        <h:html xmlns:h="http://www.w3.org/1999/xhtml"
                xmlns="http://www.w3.org/2002/xforms">
          <h:head><h:title>Household $version</h:title><model><instance>
            <data id="household" version="$version">
              <age/><meta><instanceID/></meta>
            </data>
          </instance><bind nodeset="/data/age" type="$type"/></model></h:head>
          <h:body><input ref="/data/age"><label>Age</label></input></h:body>
        </h:html>
    """.trimIndent()
    private fun original(value: String = "27") = """
        <data id="household" version="1">
          <age>$value</age><meta><instanceID>$instance</instanceID></meta>
        </data>
    """.trimIndent()

    private data class Fixture(
        val binding: QuarantinedWorkDraft,
        val source: AuthorizedReviewedSource,
        val review: TypedTransferPreflight
    )

    private fun fixture(
        value: String = "27",
        old: String = form("1"),
        newer: String = form("2")
    ): Fixture {
        val xml = original(value)
        val plan = HistoricalSchemaMappingEngine.plan(
            work, source, target, RecordWorkType.AMENDMENT,
            xml, old, newer, "household", "2"
        )
        val review = MappingReviewSnapshot(
            work, plan.sourceXFormSha256, plan.targetXFormSha256,
            mapOf("/data/age" to MappingDecision.REENTER), true
        )
        val evidence = ReviewedScalarTransferProbe(
            disk.newFolder("source-probe-" + UUID.randomUUID())
        ).validate(
            plan, review, xml,
            ReviewedScalarTransferProbe.sha256(xml), instance, old, newer
        )
        val preview = PreparedRecordPreview(
            workId = work, sourceSubmissionId = source,
            targetFormVersionId = target, targetFormId = "household",
            targetFormVersion = "2", type = RecordWorkType.AMENDMENT,
            mappingApprovalRequired = true,
            assessment = CrossVersionAssessment(
                work, source, target, RecordWorkType.AMENDMENT,
                ReviewedScalarTransferProbe.sha256(xml),
                plan.targetXFormSha256, "1", "2", 1, 1, 0,
                emptyList(), setOf(MappingIssue.JAVAROSA_EVALUATION_REQUIRED)
            ),
            mappingPlan = plan,
            nativeStaging = NativeStagingEvidence(
                work, source, target, RecordWorkType.AMENDMENT,
                plan.sourceXFormSha256, plan.targetXFormSha256,
                true, true, 1, true
            ),
            sourceInstanceId = instance, visitKey = null
        )
        val binding = QuarantinedWorkDraftVault(disk.root)
            .stage("test-project", "a".repeat(64), preview, review, evidence)
        val preflight = TypedTransferPreflight(
            work, binding.sourceXmlSha256, requireNotNull(binding.sourceXFormSha256),
            binding.targetXFormSha256, listOf("/data/age"), 0, 0, 1
        )
        return Fixture(binding, AuthorizedReviewedSource(plan, xml, old, newer), preflight)
    }

    private fun inspector() = NativeTypedValueProofProbe(
        disk.newFolder("native-value-" + UUID.randomUUID())
    )

    @Test fun validIntegerReturnsOnlyInertPathEvidence() {
        val f = fixture()
        val proof = inspector().inspect(f.binding, f.source, f.review)
        assertEquals(listOf("/data/age"), proof.verifiedPaths)
        assertFalse(proof.canCopy)
        assertFalse(proof.canWriteDraft)
        assertFalse(proof.canSubmit)
    }

    @Test fun invalidIntegerFailsUnderNativeTargetRules() {
        val f = fixture(value = "not_an_integer")
        rejects { inspector().inspect(f.binding, f.source, f.review) }
    }

    @Test fun targetTypeChangedDoesNotPromoteValue() {
        val f = fixture(newer = form("2", "decimal"))
        rejects { inspector().inspect(f.binding, f.source, f.review) }
    }

    @Test fun changedSourceXmlHashFailsBeforeValueProof() {
        val f = fixture()
        rejects { inspector().inspect(
            f.binding,
            f.source.copy(sourceXml = original("99")),
            f.review
        ) }
    }

    @Test fun forgedPreflightOrSchemaDriftFailsClosed() {
        val f = fixture()
        rejects { inspector().inspect(
            f.binding, f.source,
            f.review.copy(candidatePaths = listOf("/data/unknown"))
        ) }
        rejects { inspector().inspect(
            f.binding, f.source,
            f.review.copy(targetXFormSha256 = "f".repeat(64))
        ) }
    }

    @Test fun proofCannotGrantTransferOrSubmissionPermission() {
        val f = fixture()
        val result = inspector().inspect(f.binding, f.source, f.review)
        rejects { result.copy(canCopy = true) }
        rejects { result.copy(canWriteDraft = true) }
        rejects { result.copy(canSubmit = true) }
    }

    private fun rejects(block: () -> Unit) {
        var failed = false
        try { block() } catch (_: Exception) { failed = true }
        assertTrue("Native typed value proof must fail closed", failed)
    }
}
