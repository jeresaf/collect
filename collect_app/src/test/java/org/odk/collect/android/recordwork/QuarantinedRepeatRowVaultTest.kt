package org.odk.collect.android.recordwork

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.UUID

/** Synthetic work identities; injected JCE AES-GCM avoids real Keystore. */
class QuarantinedRepeatRowVaultTest {
    @get:Rule val disk = TemporaryFolder()
    private val project = "test-project"
    private val actor = "a".repeat(64)
    private val work = "e6bddba9-e98b-49cb-8bed-dce91d400520"
    private val source = "a8c07f51-b4c1-40a4-b31c-956d2ca5e048"
    private val target = "5a6f0bf6-795c-4d91-9861-16910542b4f1"
    private val group = "/data/members"
    private val cipher = EncryptedPrivateTextDraftStoreTest.TestAesGcmCipher()
    private val schema = PrivateRepeatSchema(
        workId = work, targetXFormSha256 = "3".repeat(64),
        groups = listOf(PrivateRepeatGroup(group, listOf(
            PrivateScalarField("$group/name", PrivateScalarType.TEXT),
            PrivateScalarField("$group/age", PrivateScalarType.INTEGER)
        )))
    )

    @Test fun stableUuidAndIdempotencySurviveReopenedVault() {
        val binding = stage()
        val request = uuid()
        val first = store().addRow(binding, schema, group, request)
        assertEquals(first, store().addRow(binding, schema, group, request))
        val later = store().read(binding, schema)
        assertEquals(listOf(first), later.active(group))
        assertEquals(1, later.rows.size)
        assertFalse(later.canSaveAnswers)
        assertFalse(later.canFinalize)
        assertFalse(later.canUpload)
        assertEquals(first.rowId, UUID.fromString(first.rowId).toString())
        assertEquals(0, first.creationSequence)
        val names = folder().listFiles().orEmpty().map { it.name }
        assertEquals(2, names.size)
        assertTrue(names.contains("repeat-row-identities.aesgcm"))
        assertTrue(names.contains("binding.properties"))
        val bytes = ledgerFile().readBytes().toString(Charsets.ISO_8859_1)
        assertFalse(bytes.contains(first.rowId))
        assertFalse(bytes.contains(first.creationRequestId))
        assertFalse(bytes.contains(group))
    }

    @Test fun archivedRowsKeepIdentityWhenLaterIndexesShift() {
        val binding = stage()
        val a = store().addRow(binding, schema, group, uuid())
        val b = store().addRow(binding, schema, group, uuid())
        val c = store().addRow(binding, schema, group, uuid())
        val op = uuid()
        val archived = store().archiveRow(binding, schema, a.rowId, op)
        assertTrue(archived.isArchived)
        assertEquals(archived, store().archiveRow(binding, schema, a.rowId, op))
        val active = store().read(binding, schema).active(group)
        assertEquals(listOf(b.rowId, c.rowId), active.map { it.rowId })
        val d = store().addRow(binding, schema, group, uuid())
        assertNotEquals(a.rowId, d.rowId)
        assertEquals(3, d.creationSequence)
        assertEquals(4, store().read(binding, schema).rows.size)
        assertEquals(a.rowId,
            store().read(binding, schema).rows.first().rowId)
        assertTrue(store().read(binding, schema).rows.first().isArchived)
        assertEquals(listOf(b.rowId, c.rowId, d.rowId),
            store().read(binding, schema).active(group).map { it.rowId })
    }

    @Test fun idempotencyCollisionAcrossCreationAndArchiveIsBlocked() {
        val binding = stage()
        val operation = uuid()
        val a = store().addRow(binding, schema, group, operation)
        reject { store().archiveRow(binding, schema, a.rowId, operation) }
        val b = store().addRow(binding, schema, group, uuid())
        val archiveRequest = uuid()
        store().archiveRow(binding, schema, a.rowId, archiveRequest)
        reject { store().archiveRow(binding, schema, b.rowId, archiveRequest) }
        reject { store().archiveRow(binding, schema, a.rowId, uuid()) }
        reject { store().archiveRow(binding, schema, uuid(), uuid()) }
        assertEquals(1, store().read(binding, schema).active(group).size)
    }

    @Test fun wrongActorSourceVisitAndTargetFormNeverRecoverRows() {
        val binding = stage()
        store().addRow(binding, schema, group, uuid())
        reject { store().read(binding.copy(actorContextSha256 = "b".repeat(64)), schema) }
        reject { store().read(binding.copy(sourceXmlSha256 = "f".repeat(64)), schema) }
        reject { store().read(binding.copy(targetXFormSha256 = "d".repeat(64)), schema) }
        reject { store().read(binding.copy(visitKey = "visit-3"), schema) }
        reject { store().read(binding, schema.copy(targetXFormSha256 = "f".repeat(64))) }
        reject { store().read(binding, schema.copy(groups = listOf(
            PrivateRepeatGroup(group, listOf(
                PrivateScalarField("$group/name", PrivateScalarType.TEXT),
                PrivateScalarField("$group/age", PrivateScalarType.DECIMAL)
            ))
        ))) }
        assertEquals(1, store().read(binding, schema).rows.size)
    }

    @Test fun failedOrForgedOperationsNeverModifyExistingRows() {
        val binding = stage()
        val created = store().addRow(binding, schema, group, uuid())
        reject { store().addRow(binding, schema, "/data/unknown", uuid()) }
        reject { store().addRow(binding, schema, group, "bad-uuid") }
        reject { store().archiveRow(binding, schema, "bad-uuid", uuid()) }
        reject { store().addRow(binding,
            schema.copy(groups = listOf(
                PrivateRepeatGroup("/data/other",
                    listOf(PrivateScalarField("/data/other/x", PrivateScalarType.TEXT)))
            )), group, uuid()) }
        val current = store().read(binding, schema)
        assertEquals(listOf(created), current.rows)
    }

    @Test fun corruptedCiphertextCannotReturnForgedRepeatIdentities() {
        val binding = stage()
        store().addRow(binding, schema, group, uuid())
        val contents = ledgerFile().readBytes()
        contents[contents.size - 1] = (contents.last().toInt() xor 1).toByte()
        ledgerFile().writeBytes(contents)
        reject { store().read(binding, schema) }
        reject { store().addRow(binding, schema, group, uuid()) }
    }

    @Test fun maximumActiveRowsFailsClosedAndCannotReuseOldId() {
        val binding = stage()
        val old = store().addRow(binding, schema, group, uuid())
        repeat(QuarantinedRepeatRowVault.MAX_ACTIVE_PER_GROUP - 1) {
            store().addRow(binding, schema, group, uuid())
        }
        assertEquals(20, store().read(binding, schema).active(group).size)
        reject { store().addRow(binding, schema, group, uuid()) }
        store().archiveRow(binding, schema, old.rowId, uuid())
        val next = store().addRow(binding, schema, group, uuid())
        assertNotEquals(old.rowId, next.rowId)
        assertEquals(20, store().read(binding, schema).active(group).size)
        assertEquals(21, store().read(binding, schema).rows.size)
    }

    @Test fun unsubmittedRowInventoriesCannotBecomeOdkInstances() {
        val binding = stage()
        val item = store().addRow(binding, schema, group, uuid())
        assertEquals(0, store().read(binding, schema).rows.count { it.isArchived })
        reject { store().read(binding, schema).copy(canUpload = true) }
        reject { store().read(binding, schema).copy(canSaveAnswers = true) }
        reject { store().read(binding, schema).copy(canFinalize = true) }
        assertNull(folder().listFiles().orEmpty().singleOrNull { it.name.endsWith(".xml") })
        assertFalse(File(disk.root, "instances").exists())
        assertTrue(item.rowId.isNotBlank())
    }

    private fun ledgerFile() = File(folder(), "repeat-row-identities.aesgcm")
    private fun folder() = File(disk.root, "recordwork-drafts/$project/$work")
    private fun store() = QuarantinedRepeatRowVault(disk.root, cipher)
    private fun uuid() = UUID.randomUUID().toString()

    private fun stage(): QuarantinedWorkDraft {
        val plan = HistoricalMappingPlan(
            workId = work, sourceSubmissionId = source,
            targetFormVersionId = target,
            workType = RecordWorkType.FOLLOW_UP,
            sourceXFormSha256 = null,
            targetXFormSha256 = "3".repeat(64),
            sourceFormId = "household", sourceVersion = "1",
            targetFormId = "household", targetVersion = "2",
            candidates = listOf(
                TargetFieldCandidate("$group/name", null,
                    setOf(MappingRisk.REPEAT, MappingRisk.DIFFERENT_FORM_FOLLOW_UP),
                    false),
                TargetFieldCandidate("$group/age", null,
                    setOf(MappingRisk.REPEAT, MappingRisk.DIFFERENT_FORM_FOLLOW_UP),
                    false)
            ), removedSourcePaths = emptySet(),
            globalRisks = setOf(MappingRisk.DIFFERENT_FORM_FOLLOW_UP)
        )
        val preview = PreparedRecordPreview(
            workId = work, sourceSubmissionId = source,
            targetFormVersionId = target,
            targetFormId = "household", targetFormVersion = "2",
            type = RecordWorkType.FOLLOW_UP,
            mappingApprovalRequired = true,
            assessment = CrossVersionAssessment(
                work, source, target, RecordWorkType.FOLLOW_UP,
                "1".repeat(64), "3".repeat(64),
                "1", "2", 2, 2, 0, emptyList(),
                setOf(MappingIssue.CROSS_FORM_FOLLOW_UP)
            ),
            mappingPlan = plan,
            nativeStaging = NativeStagingEvidence(
                work, source, target, RecordWorkType.FOLLOW_UP,
                null, "3".repeat(64), true, false, 0, false
            ),
            sourceInstanceId =
                "uuid:9e1e9e90-5cc8-4e65-a5d0-acb5bdc1500d",
            visitKey = "visit-2"
        )
        val review = MappingReviewSnapshot(
            work, null, "3".repeat(64),
            plan.candidates.associate { it.targetPath to MappingDecision.REENTER },
            true
        )
        return QuarantinedWorkDraftVault(disk.root)
            .stage(project, actor, preview, review)
    }

    private fun reject(block: () -> Unit) {
        var denied = false
        try { block() } catch (_: Exception) { denied = true }
        assertTrue("Unapproved repeat identity operation must be denied", denied)
    }
}
