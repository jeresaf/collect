package org.odk.collect.android.recordwork

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File

/** Uses injected JCE AES-GCM; production cipher is Android Keystore. */
class QuarantinedPrivateImageStoreTest {
    @get:Rule val directory = TemporaryFolder()
    private val project = "project"
    private val username = "a".repeat(64)
    private val work = "e6bddba9-e98b-49cb-8bed-dce91d400520"
    private val source = "a8c07f51-b4c1-40a4-b31c-956d2ca5e048"
    private val target = "5a6f0bf6-795c-4d91-9861-16910542b4f1"
    private val field = "/data/newPhoto"
    private val fields = listOf(field)
    private val cipher = EncryptedPrivateTextDraftStoreTest.TestAesGcmCipher()

    private fun sample(seed: String): ByteArray =
        byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0xe0.toByte()) +
            ("SYNTHETIC_IMAGE_ONLY_" + seed).toByteArray()

    @Test fun storesEncryptedOpaqueImageWithStableBoundFieldAndRestarts() {
        val binding = stage()
        val image = sample("RESTRICTED_IMAGE_DATA")
        val receipt = store().importSelectedImage(
            binding, field, fields, "image/jpeg", ByteArrayInputStream(image)
        )
        assertEquals("image/jpeg", receipt.mime)
        assertEquals(field, receipt.fieldPath)
        assertEquals(image.size, receipt.bytes)
        assertEquals(QuarantinedPrivateImageStore.digest(image), receipt.sha256)
        assertFalse(receipt.canFinalize)
        assertFalse(receipt.canSubmit)
        assertEquals(receipt, store().receipt(binding, field, fields))
        val bytes = store().withVerifiedImage(binding, field, fields) { _, data ->
            data.copyOf()
        }
        assertArrayEquals(image, bytes)
        val files = protectedFolder().listFiles().orEmpty()
        assertEquals(2, files.size)
        assertTrue(files.any { it.name == "binding.properties" })
        assertTrue(files.any { it.name.startsWith("image-") &&
            it.name.endsWith(".aesgcm") })
        val ciphertext = files.single { it.name.endsWith(".aesgcm") }.readBytes()
        assertFalse(String(ciphertext, Charsets.ISO_8859_1).contains(
            "SYNTHETIC_IMAGE_ONLY_RESTRICTED_IMAGE_DATA"
        ))
        assertFalse(files.any { it.name.endsWith(".xml") || it.name.endsWith(".jpg") })
    }

    @Test fun absentFieldIsNotConfusedWithOriginalSubmissionImage() {
        val binding = stage()
        assertNull(store().receipt(binding, field, fields))
        assertNull(store().withVerifiedImage(binding, field, fields) { _, _ -> true })
    }

    @Test fun replacementAndClearAffectOnlyThePrivateNewImage() {
        val binding = stage()
        store().importSelectedImage(binding, field, fields,
            "image/jpeg", ByteArrayInputStream(sample("FIRST")))
        val first = store().receipt(binding, field, fields)
        store().importSelectedImage(binding, field, fields,
            "image/jpeg", ByteArrayInputStream(sample("SECOND")))
        val second = store().receipt(binding, field, fields)
        assertNotEquals(first?.sha256, second?.sha256)
        store().clear(binding, field, fields)
        assertNull(store().receipt(binding, field, fields))
        assertTrue(File(protectedFolder(), "binding.properties").exists())
    }

    @Test fun tamperingWithCiphertextOrClaimedMimeIsRejected() {
        val binding = stage()
        rejects {
            store().importSelectedImage(binding, field, fields,
                "image/png", ByteArrayInputStream(sample("WRONG_MIME")))
        }
        rejects {
            store().importSelectedImage(binding, field, fields,
                "image/gif", ByteArrayInputStream(sample("UNSUPPORTED")))
        }
        rejects {
            store().importSelectedImage(binding, field, fields,
                "image/jpeg", ByteArrayInputStream("not really an image".toByteArray()))
        }
        assertNull(store().receipt(binding, field, fields))
        store().importSelectedImage(binding, field, fields,
            "image/jpeg", ByteArrayInputStream(sample("ACTUAL")))
        val file = protectedFolder().listFiles().orEmpty().single {
            it.name.endsWith(".aesgcm")
        }
        val changed = file.readBytes()
        changed[changed.size - 1] = (changed.last().toInt() xor 1).toByte()
        file.writeBytes(changed)
        rejects { store().receipt(binding, field, fields) }
    }

    @Test fun oversizedOrEmptyImagesFailBeforeOverwritingExistingCiphertext() {
        val binding = stage()
        val original = store().importSelectedImage(binding, field, fields,
            "image/jpeg", ByteArrayInputStream(sample("PRESERVE_ME")))
        rejects {
            store().importSelectedImage(binding, field, fields, "image/jpeg",
                ByteArrayInputStream(ByteArray(
                    QuarantinedPrivateImageStore.MAX_IMAGE_BYTES + 1
                ) { 0x51.toByte() }))
        }
        rejects {
            store().importSelectedImage(binding, field, fields, "image/jpeg",
                ByteArrayInputStream(byteArrayOf()))
        }
        assertEquals(original, store().receipt(binding, field, fields))
        assertEquals(2, protectedFolder().listFiles().orEmpty().size)
    }

    @Test fun changedActorVersionOrSourceHashDoesNotReadPrivateAssets() {
        val binding = stage()
        store().importSelectedImage(binding, field, fields,
            "image/jpeg", ByteArrayInputStream(sample("PERSONAL")))
        rejects {
            store().receipt(binding.copy(actorContextSha256 = "b".repeat(64)),
                field, fields)
        }
        rejects {
            store().receipt(binding.copy(targetXFormSha256 = "e".repeat(64)),
                field, fields)
        }
        rejects {
            store().receipt(binding.copy(sourceXmlSha256 = "d".repeat(64)),
                field, fields)
        }
        rejects {
            store().receipt(binding.copy(workId =
                "00000000-0000-4000-8000-000000000000"), field, fields)
        }
        assertNotNull(store().receipt(binding, field, fields))
    }

    @Test fun unapprovedPathAndPathTraversalAreNotAccepted() {
        val binding = stage()
        rejects { store().importSelectedImage(binding, "/data/../../secret",
            fields, "image/jpeg", ByteArrayInputStream(sample("ESCAPE"))) }
        rejects { store().importSelectedImage(binding, "/data/other",
            fields, "image/jpeg", ByteArrayInputStream(sample("UNREVIEWED"))) }
        rejects { store().importSelectedImage(binding, field,
            listOf("/data/other"), "image/jpeg", ByteArrayInputStream(sample("UNREVIEWED"))) }
        assertEquals(1, protectedFolder().listFiles().orEmpty().size)
    }

    @Test fun claimedPngAndWebpRequireExactFileMagic() {
        val binding = stage()
        val png = byteArrayOf(
            0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a,
            0x51, 0x51, 0x51, 0x51
        )
        assertEquals("image/png", store().importSelectedImage(
            binding, field, fields, "image/png", ByteArrayInputStream(png)
        ).mime)
        val webp = "RIFF0000WEBPWEBP_TEST".toByteArray()
        assertEquals("image/webp", store().importSelectedImage(
            binding, field, fields, "image/webp", ByteArrayInputStream(webp)
        ).mime)
    }

    @Test fun protectedMediaReceiptsCannotBePromotedToSubmissions() {
        val binding = stage()
        val receipt = store().importSelectedImage(binding, field, fields,
            "image/jpeg", ByteArrayInputStream(sample("PRIVATE")))
        rejects { receipt.copy(canSubmit = true) }
        rejects { receipt.copy(canFinalize = true) }
        assertFalse(File(directory.root, "instances").exists())
    }

    private fun store() = QuarantinedPrivateImageStore(directory.root, cipher)

    private fun protectedFolder(): File =
        File(directory.root, "recordwork-drafts/$project/$work")

    private fun stage(): QuarantinedWorkDraft {
        val plan = HistoricalMappingPlan(
            workId = work, sourceSubmissionId = source, targetFormVersionId = target,
            workType = RecordWorkType.FOLLOW_UP,
            sourceXFormSha256 = null, targetXFormSha256 = "3".repeat(64),
            sourceFormId = "household", sourceVersion = "1",
            targetFormId = "household", targetVersion = "2",
            candidates = listOf(
                TargetFieldCandidate(field, null,
                    setOf(MappingRisk.DIFFERENT_FORM_FOLLOW_UP, MappingRisk.MEDIA),
                    false)
            ), removedSourcePaths = emptySet(),
            globalRisks = setOf(MappingRisk.DIFFERENT_FORM_FOLLOW_UP)
        )
        val preview = PreparedRecordPreview(
            workId = work, sourceSubmissionId = source, targetFormVersionId = target,
            targetFormId = "household", targetFormVersion = "2",
            type = RecordWorkType.FOLLOW_UP, mappingApprovalRequired = true,
            assessment = CrossVersionAssessment(
                work, source, target, RecordWorkType.FOLLOW_UP,
                "1".repeat(64), "3".repeat(64), "1", "2", 1, 1, 0,
                emptyList(), setOf(MappingIssue.CROSS_FORM_FOLLOW_UP)
            ), mappingPlan = plan,
            nativeStaging = NativeStagingEvidence(
                work, source, target, RecordWorkType.FOLLOW_UP,
                null, "3".repeat(64), true, false, 0, false
            ),
            sourceInstanceId = "uuid:9e1e9e90-5cc8-4e65-a5d0-acb5bdc1500d",
            visitKey = "visit-2"
        )
        val review = MappingReviewSnapshot(
            work, null, "3".repeat(64),
            mapOf(field to MappingDecision.REENTER), true
        )
        return QuarantinedWorkDraftVault(directory.root)
            .stage(project, username, preview, review)
    }

    private fun rejects(block: () -> Unit) {
        var denied = false
        try { block() } catch (_: Exception) { denied = true }
        assertTrue("Unsafe private media action must be refused", denied)
    }
}
