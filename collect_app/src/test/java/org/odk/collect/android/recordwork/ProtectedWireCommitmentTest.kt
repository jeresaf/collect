package org.odk.collect.android.recordwork

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.json.JSONObject
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ProtectedWireCommitmentTest {
    private fun id(n: Int) = "00000000-0000-4000-8000-" + n.toString().padStart(12, '0')
    private val local = ProtectedAttemptedPackageEvidence(15,
        "c13861b386c0f5b3d6c48c8400d2baf858a393533cb01506e681898e05adffe1",
        listOf(
            ProtectedAttachmentEvidence("a.png", "image/png", 3,
                "039058c6f2c0cb492c533b0a4d14ef77cc0f78abccced5287d84a1a2011cfb81"),
            ProtectedAttachmentEvidence("b.jpg", "image/jpeg", 3,
                "787c798e39a5bc1910355bae6d0cd87a36b2e10fd0202a83e3bb6b005da83472")
        ))

    private fun compute(e: ProtectedAttemptedPackageEvidence = local, attempt: Int = 6) =
        ProtectedWireCommitment.compute(id(1), id(2), id(3), id(4), id(5), id(attempt),
            "uuid:" + id(7), RecordWorkType.FOLLOW_UP, e)

    @Test
    fun canonicalVectorAndOrdering() {
        assertEquals("d0f6d79d8a42fa9932499d9fc0e801e023a1289a51e7b06776b2d7f79e06bbdd", compute())
        assertEquals(compute(), compute(local.copy(attachments = local.attachments.reversed())))
        assertNotEquals(compute(), compute(attempt = 8))
        assertNotEquals(compute(), compute(local.copy(xmlBytes = 16)))
        assertThrows(IllegalArgumentException::class.java) {
            compute(local.copy(attachments = listOf(local.attachments.first(), local.attachments.first())))
        }
        assertThrows(IllegalArgumentException::class.java) {
            compute(local.copy(attachments = listOf(local.attachments.first().copy(mime = "image/webp"))))
        }
    }

    private fun view(wire: Any = JSONObject.NULL, accepted: Any = JSONObject.NULL) =
        JSONObject().put("workId", id(3)).put("attemptId", id(6)).put("clientRequestId", id(8))
            .put("projectId", id(1)).put("actorId", id(2)).put("sourceId", id(4))
            .put("targetVersionId", id(5)).put("formId", id(9)).put("instanceId", "uuid:" + id(7))
            .put("wireSha256", wire).put("acceptedSubmissionId", accepted)

    @Test
    fun statusRequiresExactShapeAndCoherentState() {
        fun status(state: String, a: JSONObject, completion: Any = JSONObject.NULL) =
            JSONObject().put("state", state).put("attempt", a).put("completion", completion)
        assertEquals(RemoteProtectedAttemptState.RESERVED_UNBOUND,
            RemoteProtectedAttemptStatus.parse(status("RESERVED_UNBOUND", view())).state)
        assertEquals(RemoteProtectedAttemptState.BOUND_EXACT_CONTENT_ADMISSIBLE,
            RemoteProtectedAttemptStatus.parse(status("BOUND_EXACT_CONTENT_ADMISSIBLE", view(compute()))).state)
        assertThrows(IllegalArgumentException::class.java) {
            RemoteProtectedAttemptStatus.parse(status("BOUND_EXACT_CONTENT_ADMISSIBLE", view()))
        }
        assertThrows(IllegalArgumentException::class.java) {
            RemoteProtectedAttemptStatus.parse(status("COMPLETED_VERIFIED", view(compute(), id(10))))
        }
        assertThrows(IllegalArgumentException::class.java) {
            RemoteProtectedAttempt.parse(view().put("unexpected", true))
        }
        assertThrows(IllegalArgumentException::class.java) {
            RemoteProtectedAttempt.parse(view().put("wireSha256", "invalid"))
        }
    }
}
