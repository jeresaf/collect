package org.odk.collect.android.recordwork

import java.io.File
import java.nio.file.Files
import java.util.UUID

/** Work-scoped conservative guard; no marker is ever cleared here.
 * Callers mutating draft state hold the same process-local monitor as sender.
 */
internal object ProtectedSubmissionAttemptGuard {
    private val markers = listOf("submission-attempt.aesgcm", "submitted-receipt.aesgcm",
        ".submitted-receipt-write", "protected-attempt-request.aesgcm",
        "protected-attempt-server.aesgcm", "protected-bound-receipt.aesgcm")

    fun hasAttempt(root: File, binding: QuarantinedWorkDraft): Boolean =
        synchronized(ProtectedDraftComponentLock.monitor) {
            val base = root.canonicalFile
            require(binding.projectId.matches(Regex("[A-Za-z0-9_-]{1,128}")) &&
                UUID.fromString(binding.workId).toString() == binding.workId)
            val committed = File(base, "recordwork-committed")
            val project = File(committed, binding.projectId)
            val work = File(project, binding.workId)
            for (dir in listOf(committed, project, work)) {
                require(!Files.isSymbolicLink(dir.toPath()) &&
                    dir.canonicalFile == dir.absoluteFile) { "Unsafe protected submission directory" }
                if (!dir.exists()) return@synchronized false
                require(dir.isDirectory) { "Unsafe protected submission directory" }
            }
            markers.any {
                val file = File(work, it)
                file.exists() || Files.isSymbolicLink(file.toPath())
            }
        }

    fun requireEditable(root: File, binding: QuarantinedWorkDraft) {
        require(!hasAttempt(root, binding)) { "Submission attempt requires reconciliation" }
    }
}
