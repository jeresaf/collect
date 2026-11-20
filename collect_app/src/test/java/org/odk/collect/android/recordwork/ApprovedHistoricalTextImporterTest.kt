package org.odk.collect.android.recordwork

import org.javarosa.model.xform.XFormsModule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.UUID

/** Synthetic original/current JavaRosa models, no actual household records. */
class ApprovedHistoricalTextImporterTest {
    @get:Rule val disk = TemporaryFolder()
    private val project = "test-project"
    private val actor = "a".repeat(64)
    private val work = "e6bddba9-e98b-49cb-8bed-dce91d400520"
    private val sourceId = "a8c07f51-b4c1-40a4-b31c-956d2ca5e048"
    private val targetId = "5a6f0bf6-795c-4d91-9861-16910542b4f1"
    private val instance = "uuid:9e1e9e90-5cc8-4e65-a5d0-acb5bdc1500d"
    private val oldAnswer = "SYNTHETIC_SOURCE_ANSWER_NEVER_PLAINTEXT"
    private val originalXml = """
        <data id="household" version="1">
          <name>$oldAnswer</name><count>27</count>
          <meta><instanceID>$instance</instanceID></meta>
        </data>
    """.trimIndent()
    private fun form(version: String, ageType: String = "int") = """
        <h:html xmlns:h="http://www.w3.org/1999/xhtml"
                xmlns="http://www.w3.org/2002/xforms">
          <h:head><h:title>Household $version</h:title>
            <model><instance>
              <data id="household" version="$version">
                <name/><count/><meta><instanceID/></meta>
              </data>
            </instance>
              <bind nodeset="/data/name" type="string"/>
              <bind nodeset="/data/count" type="$ageType"/>
            </model>
          </h:head>
          <h:body>
            <input ref="/data/name"><label>Name</label></input>
            <input ref="/data/count"><label>Count</label></input>
          </h:body>
        </h:html>
    """.trimIndent()

    private val oldForm = form("1")
    private val targetForm = form("2")
    private val cipher = EncryptedPrivateTextDraftStoreTest.TestAesGcmCipher()

    @Before fun register() {
        XFormsModule().registerModule()
    }

    private data class Fixture(
        val binding: QuarantinedWorkDraft,
        val plan: HistoricalMappingPlan,
        val approval: ProtectedMappingApproval,
        val native: JavaRosaPrivateScalarSession
    )

    private fun stage(
        original: String = originalXml,
        sourceForm: String = oldForm,
        target: String = targetForm
    ): Fixture {
        val plan = HistoricalSchemaMappingEngine.plan(
            work, sourceId, targetId, RecordWorkType.AMENDMENT,
            original, sourceForm, target, "household", "2"
        )
        val snapshot = HistoricalMappingReview(plan).apply {
            plan.candidates.forEach { candidate ->
                decide(candidate.targetPath, if (candidate.targetPath == "/data/name")
                    MappingDecision.REQUEST_REVIEWED_TRANSFER
                else MappingDecision.REENTER)
            }
        }.snapshot()
        val evidence = ReviewedScalarTransferProbe(
            disk.newFolder("native-evidence-" + UUID.randomUUID())
        ).validate(
            plan, snapshot, original,
            ReviewedScalarTransferProbe.sha256(original), instance,
            sourceForm, target
        )
        val preview = PreparedRecordPreview(
            workId = work, sourceSubmissionId = sourceId,
            targetFormVersionId = targetId,
            targetFormId = "household", targetFormVersion = "2",
            type = RecordWorkType.AMENDMENT, mappingApprovalRequired = true,
            assessment = CrossVersionAssessment(
                work, sourceId, targetId, RecordWorkType.AMENDMENT,
                ReviewedScalarTransferProbe.sha256(original),
                plan.targetXFormSha256, "1", "2", 2, 2, 1,
                emptyList(), setOf(MappingIssue.JAVAROSA_EVALUATION_REQUIRED)
            ),
            mappingPlan = plan,
            nativeStaging = NativeStagingEvidence(
                work, sourceId, targetId, RecordWorkType.AMENDMENT,
                plan.sourceXFormSha256, plan.targetXFormSha256,
                true, true, plan.candidates.size, true
            ),
            sourceInstanceId = instance,
            visitKey = null
        )
        val binding = QuarantinedWorkDraftVault(disk.root).stage(
            project, actor, preview, snapshot, evidence
        )
        val approval = approvals().save(binding, plan, snapshot)
        val native = JavaRosaPrivateScalarSession(
            disk.newFolder("native-target-" + UUID.randomUUID()),
            binding, plan, target
        )
        return Fixture(binding, plan, approval, native)
    }

    private fun approvals() = ProtectedMappingApprovalStore(disk.root, cipher)
    private fun drafts() = EncryptedPrivateTextDraftStore(disk.root, cipher)
    private fun approvalFile() =
        File(disk.root, "recordwork-drafts/$project/$work/mapping-approval.aesgcm")
    private fun importer() =
        ApprovedHistoricalTextImporter(disk.newFolder("import-" + UUID.randomUUID()))
    private fun authorized(f: Fixture, xml: String = originalXml,
        old: String = oldForm, newer: String = targetForm
    ) = AuthorizedReviewedSource(f.plan, xml, old, newer)

    @Test fun durableDecisionsSurviveRestartWithoutPersistingOriginalValues() {
        val f = stage()
        val recovered = approvals().read(f.binding, f.plan)
        assertEquals(f.approval, recovered)
        assertEquals(setOf("/data/name"), recovered?.approvedTransferPaths)
        assertEquals(MappingDecision.REENTER, recovered?.decisions?.get("/data/count"))
        assertTrue(approvalFile().isFile)
        assertFalse(approvalFile().readBytes().toString(Charsets.ISO_8859_1)
            .contains(oldAnswer))
        assertFalse(approvalFile().parentFile.listFiles().orEmpty().any {
            it.name.endsWith(".xml")
        })
        assertFalse(f.approval.canUpload)
        assertFalse(f.approval.canFinalize)
        rejects { f.approval.copy(canUpload = true) }
        rejects { f.approval.copy(canFinalize = true) }
    }

    @Test fun explicitVerifiedHistoricalTextIsReenteredIntoFreshTargetOnly() {
        val f = stage()
        assertFalse(drafts().read(f.binding, f.native.fields).hasSavedDraft)
        val imported = importer().validateAndPropose(
            f.binding, f.approval, authorized(f), f.native
        )
        assertEquals(oldAnswer, imported["/data/name"])
        assertEquals("", imported["/data/count"])
        assertEquals(2, imported.size)
        assertTrue(drafts().saveIfAbsent(f.binding, imported, f.native.fields))
        val reopened = drafts().read(f.binding, f.native.fields)
        assertEquals(imported, reopened.answers)
        assertFalse(reopened.canFinalize)
        assertFalse(reopened.canSubmit)
        assertNotEquals(f.binding.sourceInstanceId, f.binding.reservedInstanceId)
        assertTrue(disk.root.listFiles().orEmpty().none { it.name.endsWith(".xml") })
    }

    @Test fun oldValuesCannotOverwriteAnyAlreadySavedOperatorDraft() {
        val f = stage()
        drafts().save(f.binding,
            mapOf("/data/name" to "MANUALLY_ENTERED",
                "/data/count" to "40"),
            f.native.fields)
        val candidate = importer().validateAndPropose(
            f.binding, f.approval, authorized(f), f.native
        )
        rejects { drafts().saveIfAbsent(f.binding, candidate, f.native.fields) }
        assertEquals("MANUALLY_ENTERED",
            drafts().read(f.binding, f.native.fields).answers["/data/name"])
    }

    @Test fun duplicateImportDoesNotEraseFirstSavedAnswers() {
        val f = stage()
        val values = importer().validateAndPropose(
            f.binding, f.approval, authorized(f), f.native
        )
        drafts().saveIfAbsent(f.binding, values, f.native.fields)
        rejects { drafts().saveIfAbsent(f.binding, values, f.native.fields) }
        assertEquals(values, drafts().read(f.binding, f.native.fields).answers)
    }

    @Test fun immutableManifestRejectsChangedDecisionWithoutErasingApproval() {
        val f = stage()
        val altered = MappingReviewSnapshot(
            work, f.plan.sourceXFormSha256, f.plan.targetXFormSha256,
            mapOf("/data/name" to MappingDecision.REENTER,
                "/data/count" to MappingDecision.REENTER), true
        )
        rejects { approvals().save(f.binding, f.plan, altered) }
        assertEquals(f.approval, approvals().read(f.binding, f.plan))
    }

    @Test fun sourceChangedAfterApprovalCannotBeTransferred() {
        val f = stage()
        rejects { importer().validateAndPropose(
            f.binding, f.approval, authorized(
                f, originalXml.replace(oldAnswer, "CHANGED_WITHOUT_REVIEW")
            ), f.native
        ) }
        assertFalse(drafts().read(f.binding, f.native.fields).hasSavedDraft)
    }

    @Test fun changedHistoricalXFormOrTargetFormRejectsOldAnswerCopy() {
        val f = stage()
        rejects { importer().validateAndPropose(
            f.binding, f.approval, authorized(
                f, old = oldForm.replace("Household 1", "Wrong original form")
            ), f.native
        ) }
        rejects { importer().validateAndPropose(
            f.binding, f.approval, authorized(
                f, newer = targetForm.replace("Household 2", "Wrong target form")
            ), f.native
        ) }
    }

    @Test fun approvalCannotBeReopenedWithWrongActorOrTamperedCiphertext() {
        val f = stage()
        rejects { approvals().read(
            f.binding.copy(actorContextSha256 = "b".repeat(64)), f.plan
        ) }
        rejects { approvals().read(
            f.binding.copy(sourceXmlSha256 = "0".repeat(64)), f.plan
        ) }
        rejects { approvals().read(
            f.binding.copy(targetXFormSha256 = "f".repeat(64)), f.plan
        ) }
        val bytes = approvalFile().readBytes()
        bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
        approvalFile().writeBytes(bytes)
        rejects { approvals().read(f.binding, f.plan) }
    }

    @Test fun forgedApprovalCannotTransferNonTextOrUnapprovedChoices() {
        val f = stage()
        val changed = f.approval.copy(decisions = mapOf(
            "/data/name" to MappingDecision.REENTER,
            "/data/count" to MappingDecision.REQUEST_REVIEWED_TRANSFER
        ))
        rejects { importer().validateAndPropose(
            f.binding, changed, authorized(f), f.native
        ) }
        val forged = f.approval.copy(decisions = mapOf(
            "/data/name" to MappingDecision.REQUEST_REVIEWED_TRANSFER,
            "/data/count" to MappingDecision.REQUEST_REVIEWED_TRANSFER
        ))
        rejects { importer().validateAndPropose(
            f.binding, forged, authorized(f), f.native
        ) }
    }

    @Test fun missingManifestIsNotImplicitPermissionForOldAnswerPrefill() {
        val f = stage()
        assertTrue(approvalFile().delete())
        assertNull(approvals().read(f.binding, f.plan))
        assertFalse(drafts().read(f.binding, f.native.fields).hasSavedDraft)
    }

    private fun rejects(block: () -> Unit) {
        var failed = false
        try { block() } catch (_: Exception) { failed = true }
        assertTrue("Unsafe approval or historical transfer must fail closed", failed)
    }
}
