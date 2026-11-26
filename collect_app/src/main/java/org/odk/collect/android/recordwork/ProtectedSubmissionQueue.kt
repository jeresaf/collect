package org.odk.collect.android.recordwork

import java.io.File
import java.nio.file.Files
import java.util.UUID

/**
 * Read-only, offline projection of the durable local artifacts. A filename
 * never proves finalized content, an accepted submission or retry authority.
 * No remote calls, decryption of answers, journal changes or send capabilities.
 */
internal class ProtectedSubmissionQueue(private val root: File) {
    internal enum class State {
        CHECKPOINT_RECHECK, FINALIZATION_RECHECK, LEGACY_UNCONFIRMED,
        BOUND_STATUS_REQUIRED, RECEIPT_RECHECK, UNAVAILABLE
    }

    internal data class Entry(val workId: String, val state: State, val boundStatusCheck: Boolean = false)

    fun list(projectId: String, actorContextSha256: String): List<Entry> =
        synchronized(ProtectedDraftComponentLock.monitor) {
            require(projectId.matches(Regex("[A-Za-z0-9_-]{1,128}")) &&
                actorContextSha256.matches(Regex("[0-9a-f]{64}")))
            val base = root.canonicalFile
            require(base.isDirectory)
            val vaultRoot = File(base, QuarantinedWorkDraftVault.VAULT_DIRECTORY)
            val project = File(vaultRoot, projectId)
            if (!safeDirectory(vaultRoot) || !safeDirectory(project)) return@synchronized emptyList()
            val vault = QuarantinedWorkDraftVault(base)
            val result = mutableListOf<Entry>()
            var count = 0
            Files.newDirectoryStream(project.toPath()).use { children ->
                for (child in children) {
                    require(++count <= MAX_DIRECTORIES) { "Local queue scan limit exceeded" }
                    val work = child.toFile()
                    if (!canonicalUuid(work.name) || Files.isSymbolicLink(child) ||
                        !work.isDirectory || work.canonicalFile != work.absoluteFile) continue
                    // Do not display or count a foreign/corrupt binding.
                    val b = runCatching { vault.read(projectId, work.name, actorContextSha256) }
                        .getOrNull() ?: continue
                    if (b.workType != RecordWorkType.FOLLOW_UP || b.validationTransfers != 0) continue
                    val state = runCatching { state(base, b) }.getOrElse { State.UNAVAILABLE }
                    if (state != null) {
                        val bound = state in setOf(State.BOUND_STATUS_REQUIRED, State.RECEIPT_RECHECK) &&
                            runCatching { ProtectedSubmissionAttemptGuard.hasBoundAttempt(base, b) }.getOrDefault(false)
                        result.add(Entry(b.workId, state, bound))
                    }
                }
            }
            result.sortedWith(compareBy<Entry> { it.state.ordinal }.thenBy { it.workId })
        }

    private fun state(base: File, b: QuarantinedWorkDraft): State? {
        val committed = File(base, "recordwork-committed")
        val project = File(committed, b.projectId)
        val area = File(project, b.workId)
        if (!safeDirectory(committed) || !safeDirectory(project) || !safeDirectory(area)) return null

        fun present(name: String): Boolean {
            val file = File(area, name)
            return file.exists() || Files.isSymbolicLink(file.toPath())
        }

        if (ProtectedSubmissionAttemptGuard.hasAttempt(base, b)) {
            if (present("protected-bound-receipt.aesgcm") ||
                present("submitted-receipt.aesgcm") || present(".submitted-receipt-write")) {
                return State.RECEIPT_RECHECK
            }
            return if (ProtectedSubmissionAttemptGuard.hasBoundAttempt(base, b))
                State.BOUND_STATUS_REQUIRED else State.LEGACY_UNCONFIRMED
        }

        // A stale generation or malformed file is still only a candidate.
        var count = 0
        Files.newDirectoryStream(area.toPath()).use { children ->
            var finalized = false
            for (child in children) {
                require(++count <= MAX_DIRECTORIES)
                val name = child.fileName.toString()
                if (name.startsWith("finalized-") && name.endsWith(".aesgcm") &&
                    canonicalUuid(name.removePrefix("finalized-").removeSuffix(".aesgcm"))) {
                    finalized = true
                }
            }
            if (finalized) return State.FINALIZATION_RECHECK
        }
        return if (present("current.aesgcm")) State.CHECKPOINT_RECHECK else null
    }

    private fun safeDirectory(dir: File): Boolean {
        require(!Files.isSymbolicLink(dir.toPath()) && dir.canonicalFile == dir.absoluteFile)
        if (!dir.exists()) return false
        require(dir.isDirectory)
        return true
    }

    private fun canonicalUuid(value: String) =
        runCatching { UUID.fromString(value).toString() == value }.getOrDefault(false)

    companion object {
        private const val MAX_DIRECTORIES = 1024
    }
}
