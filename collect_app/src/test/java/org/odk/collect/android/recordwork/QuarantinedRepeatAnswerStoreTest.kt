package org.odk.collect.android.recordwork

import org.javarosa.model.xform.XFormsModule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.UUID

/**
 * End-to-end synthetic JavaRosa and injected AES-GCM tests. The original
 * submission XML is never imported as a target instance or stored on disk.
 */
class QuarantinedRepeatAnswerStoreTest {
    @get:Rule val disk = TemporaryFolder()
    private val project = "test-project"
    private val actor = "a".repeat(64)
    private val work = "e6bddba9-e98b-49cb-8bed-dce91d400520"
    private val source = "a8c07f51-b4c1-40a4-b31c-956d2ca5e048"
    private val target = "5a6f0bf6-795c-4d91-9861-16910542b4f1"
    private val group = "/data/members"
    private val namePath = "$group/name"
    private val agePath = "$group/age"
    private val datePath = "$group/visitDate"
    private val cipher = EncryptedPrivateTextDraftStoreTest.TestAesGcmCipher()
    private val oldXml = """
        <data id="household" version="1"><meta>
          <instanceID>uuid:9e1e9e90-5cc8-4e65-a5d0-acb5bdc1500d>
          </instanceID></meta></data>
    """.trimIndent()

    @Before fun register() { XFormsModule().registerModule() }

    private fun form(version: String = "2", dateType: String = "date"): String = """
        <h:html xmlns:h="http://www.w3.org/1999/xhtml"
                xmlns="http://www.w3.org/2002/xforms">
          <h:head><h:title>Repeated household visits</h:title>
            <model><instance>
              <data id="household" version="$version">
                <members><name/><age/><visitDate/></members>
                <meta><instanceID/></meta>
              </data>
            </instance>
              <bind nodeset="/data/members/name" type="string"/>
              <bind nodeset="/data/members/age" type="int"/>
              <bind nodeset="/data/members/visitDate" type="$dateType"/>
            </model>
          </h:head>
          <h:body>
            <repeat nodeset="/data/members">
              <input ref="/data/members/name"><label>Member name</label></input>
              <input ref="/data/members/age"><label>Member age</label></input>
              <input ref="/data/members/visitDate"><label>Date</label></input>
            </repeat>
          </h:body>
        </h:html>
    """.trimIndent()

    private data class Fixture(
        val binding: QuarantinedWorkDraft,
        val session: JavaRosaPrivateRepeatAnswerSession
    )

    private fun fixture(targetForm: String = form()): Fixture {
        val plan = HistoricalSchemaMappingEngine.plan(
            work, source, target, RecordWorkType.FOLLOW_UP,
            oldXml, null, targetForm, "household", "2"
        )
        val prepared = PreparedRecordPreview(
            workId = work, sourceSubmissionId = source,
            targetFormVersionId = target,
            targetFormId = "household", targetFormVersion = "2",
            type = RecordWorkType.FOLLOW_UP, mappingApprovalRequired = true,
            assessment = CrossVersionAssessment(
                work, source, target, RecordWorkType.FOLLOW_UP,
                JavaRosaPrivateScalarSession.digest(oldXml),
                JavaRosaPrivateScalarSession.digest(targetForm),
                "1", "2", plan.candidates.size, plan.candidates.size,
                0, emptyList(), setOf(MappingIssue.CROSS_FORM_FOLLOW_UP)
            ),
            mappingPlan = plan,
            nativeStaging = NativeStagingEvidence(
                work, source, target, RecordWorkType.FOLLOW_UP,
                null, plan.targetXFormSha256, true, false, 0, false
            ),
            sourceInstanceId =
                "uuid:9e1e9e90-5cc8-4e65-a5d0-acb5bdc1500d",
            visitKey = "visit-2"
        )
        val review = MappingReviewSnapshot(
            work, null, plan.targetXFormSha256,
            plan.candidates.associate { it.targetPath to MappingDecision.REENTER },
            true
        )
        val binding = QuarantinedWorkDraftVault(disk.root)
            .stage(project, actor, prepared, review)
        val session = JavaRosaPrivateRepeatAnswerSession(
            disk.newFolder("javarosa-" + UUID.randomUUID()), binding,
            plan, targetForm
        )
        return Fixture(binding, session)
    }

    private fun rows() = QuarantinedRepeatRowVault(disk.root, cipher)
    private fun store() = QuarantinedRepeatAnswerStore(disk.root, cipher)
    private fun uuid() = UUID.randomUUID().toString()
    private fun folder() = File(disk.root, "recordwork-drafts/$project/$work")

    @Test fun nativeTypedRepeatAnswersSaveAndReopenAfterSessionRecreation() {
        val (binding, session) = fixture()
        val row = rows().addRow(binding, session.schema, group, uuid())
        val entered = mapOf(
            namePath to "Fresh visit person",
            agePath to "32",
            datePath to "2026-11-20"
        )
        val saved = store().save(binding, session, row.rowId, entered)
        assertEquals(entered, saved.values)
        assertTrue(saved.hasSavedAnswers)
        assertFalse(saved.canFinalize)
        assertFalse(saved.canUpload)
        assertEquals(saved, store().read(binding, session, row.rowId))
        val newNativeSession = JavaRosaPrivateRepeatAnswerSession(
            disk.newFolder("native-reopened"), binding,
            requireNotNull(HistoricalSchemaMappingEngine.plan(
                work, source, target, RecordWorkType.FOLLOW_UP,
                oldXml, null, form(), "household", "2"
            )), form()
        )
        assertEquals(saved, store().read(binding, newNativeSession, row.rowId))
        val file = File(folder(), "repeat-answer-" + row.rowId + ".aesgcm")
        assertTrue(file.isFile)
        assertFalse(file.readBytes().toString(Charsets.ISO_8859_1)
            .contains("Fresh visit person"))
        assertFalse(folder().listFiles().orEmpty().any {
            it.name.endsWith(".xml") || it.name.endsWith(".jpg")
        })
    }

    @Test fun deletingEarlierRowNeverReassignsSecondRowsAnswers() {
        val (binding, session) = fixture()
        val first = rows().addRow(binding, session.schema, group, uuid())
        val second = rows().addRow(binding, session.schema, group, uuid())
        val third = rows().addRow(binding, session.schema, group, uuid())
        store().save(binding, session, first.rowId, mapOf(namePath to "PERSON_A"))
        store().save(binding, session, second.rowId, mapOf(
            namePath to "PERSON_B", agePath to "42"
        ))
        store().save(binding, session, third.rowId, mapOf(
            namePath to "PERSON_C", agePath to "7"
        ))
        rows().archiveRow(binding, session.schema, first.rowId, uuid())
        assertEquals("PERSON_B",
            store().read(binding, session, second.rowId).values[namePath])
        assertEquals("PERSON_C",
            store().read(binding, session, third.rowId).values[namePath])
        rejects { store().read(binding, session, first.rowId) }
        rejects {
            store().save(binding, session, first.rowId,
                mapOf(namePath to "ARCHIVED_CANNOT_REUSE"))
        }
        val fourth = rows().addRow(binding, session.schema, group, uuid())
        assertNotEquals(first.rowId, fourth.rowId)
        assertFalse(store().read(binding, session, fourth.rowId).hasSavedAnswers)
        store().save(binding, session, fourth.rowId,
            mapOf(namePath to "FRESH_ROW"))
        assertEquals("PERSON_B",
            store().read(binding, session, second.rowId).values[namePath])
    }

    @Test fun incompleteDraftCanBeResumedThenCorrected() {
        val (binding, session) = fixture()
        val row = rows().addRow(binding, session.schema, group, uuid())
        val empty = store().read(binding, session, row.rowId)
        assertFalse(empty.hasSavedAnswers)
        store().save(binding, session, row.rowId,
            mapOf(namePath to "An incomplete entry"))
        assertEquals("", store().read(binding, session, row.rowId)
            .values[agePath])
        val corrected = store().save(binding, session, row.rowId,
            mapOf(namePath to "Corrected", agePath to "0",
                datePath to "2028-02-29"))
        assertEquals("Corrected", corrected.values[namePath])
        assertEquals("2028-02-29", corrected.values[datePath])
        assertFalse(corrected.canFinalize)
    }

    @Test fun malformedNativeValuesFailWithoutErasingTheLastSave() {
        val (binding, session) = fixture()
        val row = rows().addRow(binding, session.schema, group, uuid())
        val prior = store().save(binding, session, row.rowId,
            mapOf(namePath to "KEEP_THIS", agePath to "12"))
        for (bad in listOf("2147483648", "1.1", "NaN", " 2", "02")) {
            rejects { store().save(binding, session, row.rowId,
                mapOf(agePath to bad)) }
        }
        rejects { store().save(binding, session, row.rowId,
            mapOf(datePath to "2026-02-29")) }
        rejects { store().save(binding, session, row.rowId,
            mapOf("$group/unknown" to "UNREVIEWED")) }
        assertEquals(prior, store().read(binding, session, row.rowId))
    }

    @Test fun wrongBindingActorVersionAndUnknownRowsAreDenied() {
        val (binding, session) = fixture()
        val row = rows().addRow(binding, session.schema, group, uuid())
        store().save(binding, session, row.rowId,
            mapOf(namePath to "VALUE"))
        rejects { store().read(binding.copy(actorContextSha256 = "b".repeat(64)),
            session, row.rowId) }
        rejects { store().read(binding.copy(visitKey = "visit-3"),
            session, row.rowId) }
        rejects { store().read(binding.copy(targetXFormSha256 = "9".repeat(64)),
            session, row.rowId) }
        rejects { store().read(binding, session, uuid()) }
        assertEquals("VALUE", store().read(binding, session, row.rowId)
            .values[namePath])
    }

    @Test fun tamperedCiphertextFailsClosedAndNeverReturnsSourceAnswers() {
        val (binding, session) = fixture()
        val row = rows().addRow(binding, session.schema, group, uuid())
        store().save(binding, session, row.rowId,
            mapOf(namePath to "SYNTHETIC_SECRET_VALUE"))
        val file = File(folder(), "repeat-answer-" + row.rowId + ".aesgcm")
        val bytes = file.readBytes()
        bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
        file.writeBytes(bytes)
        rejects { store().read(binding, session, row.rowId) }
        rejects { store().save(binding, session, row.rowId,
            mapOf(namePath to "SHOULD_NOT_OVERWRITE_CORRUPTION")) }
        assertTrue(file.isFile)
    }

    @Test fun initializedNativeRepeatMustBeBlankBeforeReusingItsFirstOrdinal() {
        val unsafe = form().replace(
            "<members><name/>",
            "<members><name>UNAPPROVED_FORM_DEFAULT</name>"
        )
        val (binding, session) = fixture(targetForm = unsafe)
        val row = rows().addRow(binding, session.schema, group, uuid())
        rejects {
            store().save(binding, session, row.rowId,
                mapOf(namePath to "Never import a populated repeat"))
        }
        assertFalse(
            File(folder(), "repeat-answer-" + row.rowId + ".aesgcm").exists()
        )
    }

    @Test fun rowIdAndNativeMultiplicityMustNeverBeConfused() {
        val (binding, session) = fixture()
        val a = rows().addRow(binding, session.schema, group, uuid())
        val b = rows().addRow(binding, session.schema, group, uuid())
        val validated = session.validate(rows().read(binding, session.schema),
            mapOf(b.rowId to mapOf(namePath to "SECOND")))
        assertEquals("SECOND", validated[b.rowId]?.get(namePath))
        assertEquals("", validated[a.rowId]?.get(namePath))
        rejects { session.validate(rows().read(binding, session.schema),
            mapOf(uuid() to mapOf(namePath to "FOREIGN"))) }
        rows().archiveRow(binding, session.schema, a.rowId, uuid())
        rejects { session.validate(rows().read(binding, session.schema),
            mapOf(a.rowId to mapOf(namePath to "ARCHIVED"))) }
    }


    private fun withNativeRepeatImage(): String = form()
        .replace("<name/><age/><visitDate/>",
            "<name/><age/><visitDate/><photo/>")
        .replace(
            """<bind nodeset="/data/members/visitDate" type="date"/>""",
            """<bind nodeset="/data/members/visitDate" type="date"/>
               <bind nodeset="/data/members/photo" type="binary"/>"""
        )
        .replace(
            """</repeat>""",
            """  <upload ref="/data/members/photo" mediatype="image/*">
                   <label>New visit photo</label>
                 </upload>
               </repeat>"""
        )

    private fun repeatJpeg(marker: String) =
        byteArrayOf(
            0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0xe0.toByte()
        ) + marker.toByteArray()

    @Test fun nativeRepeatMediaOwnedByUuidNotDisplayedRowIndex() {
        val (binding, session) = fixture(withNativeRepeatImage())
        assertEquals(listOf("$group/photo"),
            session.schema.groups.single().imagePaths)
        val rowA = rows().addRow(binding, session.schema, group, uuid())
        val rowB = rows().addRow(binding, session.schema, group, uuid())
        val privateMedia = QuarantinedRepeatImageStore(disk.root, cipher)
        val first = privateMedia.importSelectedImage(
            binding, session, rowA.rowId, "$group/photo", "image/jpeg",
            java.io.ByteArrayInputStream(repeatJpeg("PERSON_A"))
        )
        val second = privateMedia.importSelectedImage(
            binding, session, rowB.rowId, "$group/photo", "image/jpeg",
            java.io.ByteArrayInputStream(repeatJpeg("PERSON_B"))
        )
        assertNotEquals(first.sha256, second.sha256)
        rows().archiveRow(binding, session.schema, rowA.rowId, uuid())
        rejects { privateMedia.receipt(binding, session, rowA.rowId, "$group/photo") }
        val rowC = rows().addRow(binding, session.schema, group, uuid())
        assertFalse(privateMedia.receipt(binding, session, rowC.rowId, "$group/photo") != null)
        assertEquals(second, privateMedia.receipt(
            binding, session, rowB.rowId, "$group/photo"
        ))
        assertEquals("PERSON_B", String(requireNotNull(
            privateMedia.withVerifiedImage(
                binding, session, rowB.rowId, "$group/photo"
            ) { _, bytes -> bytes.copyOfRange(4, bytes.size) }
        )))
        assertFalse(first.canUpload)
        assertFalse(first.canFinalize)
        rejects { first.copy(canUpload = true) }
    }

    @Test fun repeatMediaMimeSizeAndUnreviewedPathsDoNotOverwritePriorAsset() {
        val (binding, session) = fixture(withNativeRepeatImage())
        val row = rows().addRow(binding, session.schema, group, uuid())
        val media = QuarantinedRepeatImageStore(disk.root, cipher)
        val prior = media.importSelectedImage(
            binding, session, row.rowId, "$group/photo", "image/jpeg",
            java.io.ByteArrayInputStream(repeatJpeg("PRESERVE"))
        )
        rejects { media.importSelectedImage(
            binding, session, row.rowId, "$group/photo", "image/png",
            java.io.ByteArrayInputStream(repeatJpeg("FALSE_MIME"))
        ) }
        rejects { media.importSelectedImage(
            binding, session, row.rowId, "$group/photo", "image/jpeg",
            java.io.ByteArrayInputStream(ByteArray(
                QuarantinedRepeatImageStore.MAX_IMAGE_BYTES + 1
            ) { 0x41.toByte() })
        ) }
        rejects { media.importSelectedImage(
            binding, session, row.rowId, "$group/unknown", "image/jpeg",
            java.io.ByteArrayInputStream(repeatJpeg("OTHER"))
        ) }
        assertEquals(prior, media.receipt(binding, session, row.rowId, "$group/photo"))
    }

    @Test fun repeatMediaEncryptedCiphertextTamperAndWrongAccountAreRejected() {
        val (binding, session) = fixture(withNativeRepeatImage())
        val row = rows().addRow(binding, session.schema, group, uuid())
        val media = QuarantinedRepeatImageStore(disk.root, cipher)
        media.importSelectedImage(
            binding, session, row.rowId, "$group/photo", "image/jpeg",
            java.io.ByteArrayInputStream(repeatJpeg("RESTRICTED"))
        )
        rejects { media.receipt(
            binding.copy(actorContextSha256 = "b".repeat(64)),
            session, row.rowId, "$group/photo"
        ) }
        rejects { media.receipt(
            binding.copy(visitKey = "visit-3"), session, row.rowId, "$group/photo"
        ) }
        rejects { media.receipt(binding, session, uuid(), "$group/photo") }
        val encrypted = folder().listFiles().orEmpty().single {
            it.name.startsWith("repeat-image-")
        }
        val bytes = encrypted.readBytes()
        bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
        encrypted.writeBytes(bytes)
        rejects { media.receipt(binding, session, row.rowId, "$group/photo") }
    }

    @Test fun nativeRepeatImagesAndScalarAnswersRemainIndependentAndNonSubmittable() {
        val (binding, session) = fixture(withNativeRepeatImage())
        val row = rows().addRow(binding, session.schema, group, uuid())
        val values = store().save(binding, session, row.rowId,
            mapOf(namePath to "NEW_VISIT_MEMBER", agePath to "42"))
        val media = QuarantinedRepeatImageStore(disk.root, cipher)
        media.importSelectedImage(
            binding, session, row.rowId, "$group/photo", "image/jpeg",
            java.io.ByteArrayInputStream(repeatJpeg("NEW_PHOTO"))
        )
        assertEquals(values, store().read(binding, session, row.rowId))
        assertEquals("image/jpeg", media.receipt(
            binding, session, row.rowId, "$group/photo"
        )?.mime)
        assertFalse(folder().listFiles().orEmpty().any {
            it.name.endsWith(".xml") || it.name.endsWith(".jpg")
        })
    }

    private fun rejects(block: () -> Unit) {
        var blocked = false
        try { block() } catch (_: Exception) { blocked = true }
        assertTrue("Unauthorized repeated answer must fail closed", blocked)
    }
}
