package org.odk.collect.android.recordwork

import org.javarosa.core.model.Constants
import org.javarosa.core.model.data.StringData
import org.javarosa.core.model.instance.FormInstance
import org.javarosa.core.model.instance.TreeElement
import org.javarosa.core.services.transport.payload.ByteArrayPayload
import org.javarosa.model.xform.XFormSerializingVisitor
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.odk.collect.android.BuildConfig
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ProtectedAmendmentContractTest {
    private fun id(n: Int) = "00000000-0000-4000-8000-" + n.toString().padStart(12, '0')
    private val b = QuarantinedWorkDraft(
        projectId = "local-project", actorContextSha256 = "a".repeat(64), workId = id(1),
        sourceSubmissionId = id(2), sourceInstanceId = "uuid:" + id(3),
        targetFormVersionId = id(4), targetFormId = "household", targetVersion = "2",
        workType = RecordWorkType.AMENDMENT, visitKey = null, reservedInstanceId = "uuid:" + id(5),
        sourceXmlSha256 = "b".repeat(64), sourceXFormSha256 = "c".repeat(64),
        targetXFormSha256 = "d".repeat(64), reviewedPaths = 1, validationTransfers = 0,
        requiresDeprecatedId = true
    )
    private val enabled = BuildConfig.PROTECTED_BOUND_SUBMISSIONS && BuildConfig.PROTECTED_AMENDMENTS

    private fun meta() = TreeElement("meta", 0).apply {
        addChild(TreeElement("instanceID", 0).apply { dataType = Constants.DATATYPE_TEXT })
    }

    @Test fun nativeMetadataAndIndependentVerifierHonorAmendmentGate() {
        val meta = meta()
        if (!enabled) {
            assertTrue(runCatching { ProtectedReentryWork.prepareNativeMetadata(b, meta) }.isFailure)
            assertEquals(1, meta.numChildren)
            return
        }
        ProtectedReentryWork.prepareNativeMetadata(b, meta)
        requireNotNull(meta.getChild("instanceID", 0)).setValue(StringData(b.reservedInstanceId))
        assertEquals(b.sourceInstanceId, meta.getChild("deprecatedID", 0)?.value?.value)
        val root = TreeElement("data", 0).apply {
            setAttribute(null, "id", b.targetFormId)
            setAttribute(null, "version", b.targetVersion)
            addChild(meta)
        }
        val payload = XFormSerializingVisitor(false).createSerializedPayload(FormInstance(root)) as ByteArrayPayload
        val bytes = payload.payloadStream.use { it.readBytes() }
        val evidence = ProtectedNativeXmlVerifier.verify(bytes, b, "data", "members",
            emptyMap(), emptyList(), emptyMap())
        assertEquals(bytes.size, evidence.xmlBytes)
        val xml = String(bytes, Charsets.UTF_8)
        assertTrue(xml.contains("<deprecatedID>" + b.sourceInstanceId + "</deprecatedID>"))
        for (bad in listOf(xml.replace(b.sourceInstanceId, "uuid:" + id(9)),
            xml.replace("<deprecatedID>" + b.sourceInstanceId + "</deprecatedID>", ""),
            xml.replace("</meta>", "<unexpected>wrong</unexpected></meta>"))) {
            assertTrue(runCatching { ProtectedNativeXmlVerifier.verify(bad.toByteArray(Charsets.UTF_8),
                b, "data", "members", emptyMap(), emptyList(), emptyMap()) }.isFailure)
        }
        assertTrue(runCatching {
            ProtectedReentryWork.prepareNativeMetadata(b, meta().apply {
                addChild(TreeElement("deprecatedID", 0).apply {
                    dataType = Constants.DATATYPE_TEXT
                    setValue(StringData("uuid:" + id(9)))
                })
            })
        }.isFailure)
    }

    @Test fun completedAmendmentRequiresExplicitNullVisitAndExactIdentity() {
        val child = RemoteRecord(id(6), b.reservedInstanceId, id(7), b.targetFormVersionId, "")
        fun response() = JSONObject()
            .put("workId", b.workId).put("sourceSubmissionId", b.sourceSubmissionId)
            .put("sourceInstanceId", b.sourceInstanceId).put("targetFormVersionId", b.targetFormVersionId)
            .put("formId", child.formId).put("visitKey", JSONObject.NULL)
            .put("submissionId", child.submissionId).put("instanceId", child.instanceId)
            .put("xmlBytes", 20).put("xmlSha256", "1".repeat(64))
            .put("attachments", JSONArray().put(JSONObject().put("filename", "protected-" + "2".repeat(64) + ".png")
                .put("mime", "image/png").put("bytes", 3).put("sha256", "3".repeat(64))))
        if (!enabled) {
            assertTrue(runCatching { RemoteCompletionEvidence.parse(response().toString(), b, child) }.isFailure)
            return
        }
        val evidence = RemoteCompletionEvidence.parse(response().toString(), b, child)
        assertEquals(null, evidence.visitKey)
        assertFalse(evidence.canUpload || evidence.canEdit)
        val missing = response().apply { remove("visitKey") }
        for (bad in listOf(missing, response().put("visitKey", "visit"),
            response().put("sourceInstanceId", "uuid:" + id(9)))) {
            assertTrue(runCatching { RemoteCompletionEvidence.parse(bad.toString(), b, child) }.isFailure)
        }
        assertTrue(runCatching {
            RemoteCompletionEvidence.parse(response().toString(), b.copy(validationTransfers = 1), child)
        }.isFailure)
    }
}
