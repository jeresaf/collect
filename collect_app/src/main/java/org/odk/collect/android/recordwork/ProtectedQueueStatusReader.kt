package org.odk.collect.android.recordwork

import java.io.File

/**
 * One explicit GET-only check for one scoped queue entry. Returns presentation
 * state only; no attempt identity, answer, receipt ID, or upload capability.
 * The editor/sender must still independently authorize any later action.
 */
internal class ProtectedQueueStatusReader(
    private val root: File,
    private val inspect: (QuarantinedWorkDraft) -> ProtectedBoundAttempt
) {
    internal enum class Status {
        PREPARATION_INCOMPLETE, EXACT_CONTENT_ADMISSIBLE, SERVER_COMPLETED,
        ACCESS_UNVERIFIED, CONFLICT, UNAVAILABLE
    }

    fun check(projectId: String, actorContextSha256: String, workId: String): Status =
        synchronized(ProtectedDraftComponentLock.monitor) {
            try {
                val entry = ProtectedSubmissionQueue(root).list(projectId, actorContextSha256)
                    .singleOrNull { it.workId == workId }
                if (entry?.boundStatusCheck != true) return@synchronized Status.UNAVAILABLE
                val vault = QuarantinedWorkDraftVault(root)
                val binding = requireNotNull(vault.read(projectId, workId, actorContextSha256))
                val result = inspect(binding)
                require(vault.read(projectId, workId, actorContextSha256) == binding)
                val current = ProtectedSubmissionQueue(root).list(projectId, actorContextSha256)
                    .singleOrNull { it.workId == workId }
                require(current == entry)
                when (result.state) {
                    RemoteProtectedAttemptState.RESERVED_UNBOUND -> Status.PREPARATION_INCOMPLETE
                    RemoteProtectedAttemptState.BOUND_EXACT_CONTENT_ADMISSIBLE ->
                        if (entry.state == ProtectedSubmissionQueue.State.RECEIPT_RECHECK)
                            Status.CONFLICT else Status.EXACT_CONTENT_ADMISSIBLE
                    RemoteProtectedAttemptState.COMPLETED_VERIFIED -> Status.SERVER_COMPLETED
                }
            } catch (failure: ProtectedAttemptHttpException) {
                when (failure.code) {
                    401, 403, 404, 410 -> Status.ACCESS_UNVERIFIED
                    409 -> Status.CONFLICT
                    else -> Status.UNAVAILABLE
                }
            } catch (_: Exception) {
                Status.UNAVAILABLE
            }
        }
}
