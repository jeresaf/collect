package org.odk.collect.android.recordwork

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class RecordWorkContractsTest {
    private val projectId = "c77a446e-18b6-4a5a-9f52-aa12fedba010"
    private val sourceId = "a8c07f51-b4c1-40a4-b31c-956d2ca5e048"
    private val targetId = "5a6f0bf6-795c-4d91-9861-16910542b4f1"
    private val workId = "e6bddba9-e98b-49cb-8bed-dce91d400520"
    private val originalInstanceId = "uuid:9e1e9e90-5cc8-4e65-a5d0-acb5bdc1500d"

    @Test
    fun `scopes every endpoint to the configured project`() {
        val paths = RecordWorkApiPaths.fromProjectUrl(
            "https://collect.example.org/v1/projects/$projectId/"
        )
        val prefix = "https://collect.example.org/v1/projects/$projectId"

        assertEquals("$prefix/records", paths.records())
        assertEquals("$prefix/records/$sourceId/timeline", paths.timeline(sourceId))
        assertEquals("$prefix/record-work-items", paths.workItems())
        assertEquals("$prefix/record-work-items/$workId/prepare", paths.prepare(workId))
        assertEquals("$prefix/submission", paths.submission())
    }

    @Test
    fun `rejects insecure or unscoped endpoint and forged path identifiers`() {
        listOf(
            "http://collect.example.org/v1/projects/$projectId",
            "https://user:password@collect.example.org/v1/projects/$projectId",
            "https://collect.example.org/v1/projects/$projectId?key=secret",
            "https://collect.example.org/v1/projects/$projectId#fragment",
            "https://collect.example.org/v1/other/$projectId",
            "https://collect.example.org/v1/projects/$projectId/records",
            "https://collect.example.org/v1/projects/%2e%2e"
        ).forEach { reject { RecordWorkApiPaths.fromProjectUrl(it) } }

        val paths = RecordWorkApiPaths.fromProjectUrl(
            "https://collect.example.org/v1/projects/$projectId"
        )
        reject { paths.prepare("../$workId") }
        reject { paths.timeline("invalid") }
    }

    @Test
    fun `amendment cannot be accidentally scheduled as a visit`() {
        val amendment = NewRecordWork(
            sourceSubmissionId = sourceId,
            targetFormVersionId = targetId,
            workType = RecordWorkType.AMENDMENT,
            clientRequestId = "android-request-1"
        )
        assertNull(amendment.visitKey)
        reject {
            amendment.copy(visitKey = "2027-Q1-visit-2")
        }
        reject {
            amendment.copy(plannedFor = "2027-01-10T09:00:00Z")
        }
    }

    @Test
    fun `followup allows a different assigned questionnaire but demands a visit key`() {
        val followup = NewRecordWork(
            sourceSubmissionId = sourceId,
            targetFormVersionId = targetId,
            workType = RecordWorkType.FOLLOW_UP,
            clientRequestId = "android-revisit-1",
            visitKey = "2027-Q1-visit-2",
            plannedFor = "2027-01-10T09:00:00Z"
        )
        assertEquals("2027-Q1-visit-2", followup.visitKey)
        reject { followup.copy(visitKey = null) }
        reject { followup.copy(clientRequestId = "has spaces") }
    }

    @Test
    fun `unreviewed and mismatched cross-version mappings are never authorized`() {
        val prepared = ready(RecordWorkType.AMENDMENT)
        val reviewed = reviewed()

        reject { RecordWorkCompletionGate.authorize(prepared, reviewed.copy(acceptedByEnumerator = false)) }
        reject { RecordWorkCompletionGate.authorize(prepared, reviewed.copy(repeatsReviewed = false)) }
        reject { RecordWorkCompletionGate.authorize(prepared, reviewed.copy(choicesReviewed = false)) }
        reject { RecordWorkCompletionGate.authorize(prepared, reviewed.copy(attachmentsReviewed = false)) }
        reject { RecordWorkCompletionGate.authorize(prepared, reviewed.copy(targetXFormValidated = false)) }
        reject { RecordWorkCompletionGate.authorize(prepared, reviewed.copy(workItemId = sourceId)) }
        reject { RecordWorkCompletionGate.authorize(prepared, reviewed.copy(targetFormVersionId = sourceId)) }
        reject { RecordWorkCompletionGate.authorize(prepared.copy(state = "COMPLETED"), reviewed) }
        reject { RecordWorkCompletionGate.authorize(prepared.copy(mappingApprovalRequired = false), reviewed) }
    }

    @Test
    fun `amendment uses a new instance ID and original deprecatedID`() {
        val completion = RecordWorkCompletionGate.authorize(
            ready(RecordWorkType.AMENDMENT),
            reviewed()
        )

        assertTrue(completion.newInstanceId.startsWith("uuid:"))
        assertNotEquals(originalInstanceId, completion.newInstanceId)
        assertEquals(originalInstanceId, completion.deprecatedId)
        assertEquals("X-DataCollector-Work-Item-Id", completion.workItemHeaderName)
        assertEquals(workId, completion.workItemHeaderValue)
        reject {
            RecordWorkCompletionGate.authorize(
                ready(RecordWorkType.AMENDMENT), reviewed(), originalInstanceId
            )
        }
    }

    @Test
    fun `follow up creates a separate observation without deprecatedID`() {
        val completion = RecordWorkCompletionGate.authorize(
            ready(RecordWorkType.FOLLOW_UP),
            reviewed()
        )

        assertNotEquals(originalInstanceId, completion.newInstanceId)
        assertNull(completion.deprecatedId)
        assertFalse(completion.workItemHeaderValue.isBlank())
    }

    private fun ready(type: RecordWorkType) = PreparedRecordWork(
        id = workId,
        sourceInstanceId = originalInstanceId,
        sourceFormVersionId = sourceId,
        targetFormVersionId = targetId,
        workType = type,
        state = "READY",
        mappingApprovalRequired = true
    )

    private fun reviewed() = RecordMappingApproval(
        workItemId = workId,
        sourceFormVersionId = sourceId,
        targetFormVersionId = targetId,
        fieldPathsReviewed = true,
        removedAndNewQuestionsReviewed = true,
        repeatsReviewed = true,
        choicesReviewed = true,
        attachmentsReviewed = true,
        targetXFormValidated = true,
        acceptedByEnumerator = true
    )

    private fun reject(block: () -> Unit) {
        try {
            block()
        } catch (_: IllegalArgumentException) {
            return
        }
        fail("Expected a safety-contract rejection")
    }
}
