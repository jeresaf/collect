package org.odk.collect.android.recordwork

import org.odk.collect.settings.SettingsProvider
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

internal data class ProtectedCommittedDraft(
    val generation: String,
    val evidence: ProtectedCombinedRepeatEvidence
)

/** Ephemeral eligibility only; never a submission credential or native instance. */
internal data class ProtectedFinalizationEligibility(
    val workId: String,
    val generation: String,
    val reservedInstanceId: String,
    val targetFormVersionId: String,
    val inventoryDigest: String,
    val evidence: ProtectedCombinedRepeatEvidence,
    val canFinalize: Boolean = false,
    val canUpload: Boolean = false
) {
    init { require(!canFinalize && !canUpload) }
}

/**
 * A committed generation is a read-only, independently authenticated copy of
 * one coherent working-draft state. Only the authenticated atomic pointer
 * publishes it. Separate working-copy editors never write into generations.
 * No answers, binary filenames, or uploadable instances are exported.
 *
 * Process-local locking, bounded storage, fail-closed corruption handling.
 * Does not claim filesystem power-loss durability or multiprocess writers.
 */
internal class ProtectedCommittedDraftStore(
    private val scratch: File,
    private val root: File,
    private val settings: SettingsProvider,
    private val beforePublish: () -> Unit = {}
) {
    private val cipher = AndroidKeyStorePrivateDraftCipher()
    private val lock = ProtectedDraftComponentLock.monitor

    private data class Pointer(val generation: String, val inventoryDigest: String)

    fun commit(
        projectId: String, binding: QuarantinedWorkDraft, expectedGeneration: String?
    ): ProtectedCommittedDraft {
        authorize(projectId, binding)
        val id = UUID.randomUUID().toString()
        val area = area(binding)
        val candidate = File(area, id)
        var published = false
        var owned = false
        try {
            val captured = synchronized(lock) {
                checkBinding(binding)
                val previous = readPointer(binding, area)
                require(previous?.generation == expectedGeneration) { "Stale draft generation" }
                if (previous != null) verifiedGeneration(binding, area, previous)
                recoverCandidates(binding, area, previous)
                require(area.listFiles().orEmpty().count { it.isDirectory } < MAX_GENERATIONS) {
                    "Committed generation storage limit reached"
                }
                require(!candidate.exists() && !candidateMarker(area, id).exists())
                writeCandidateMarker(binding, area, id)
                check(activeCandidates.add(candidate.absolutePath))
                owned = true
                require(candidate.mkdir())
                val source = work(root, binding)
                val capturedHashes = inventory(source)
                val target = work(candidate, binding)
                require(target.mkdirs())
                for (name in capturedHashes.keys) {
                    val bytes = File(source, name).readBytes()
                    require(QuarantinedPrivateImageStore.digest(bytes) == capturedHashes[name])
                    FileOutputStream(File(target, name)).use {
                        it.write(bytes)
                        it.fd.sync()
                    }
                }
                require(inventory(target) == capturedHashes)
                capturedHashes
            }
            // Authorization and native reconstruction run outside the lock.
            val evidence = ProtectedNativeRepeatImageProbe(scratch, candidate, settings)
                .inspectCombined(projectId, binding, true)
            authorize(projectId, binding)
            beforePublish()
            synchronized(lock) {
                checkBinding(binding)
                val previous = readPointer(binding, area)
                require(previous?.generation == expectedGeneration)
                if (previous != null) verifiedGeneration(binding, area, previous)
                recoverCandidates(binding, area, previous)
                require(inventory(work(root, binding)) == captured) {
                    "Working draft changed during validation"
                }
                require(inventory(work(candidate, binding)) == captured)
                val pointer = Pointer(id, inventoryDigest(captured))
                writePointer(binding, area, pointer)
                published = true
                // If termination occurs before removing this marker, the
                // authenticated current pointer wins during recovery.
                candidateMarker(area, id).delete()
            }
            return ProtectedCommittedDraft(id, evidence)
        } finally {
            if (owned) synchronized(lock) {
                activeCandidates.remove(candidate.absolutePath)
                if (!published) {
                    removeCandidateTree(binding, candidate)
                    check(candidateMarker(area, id).delete())
                }
            }
        }
    }

    fun reopen(projectId: String, binding: QuarantinedWorkDraft): ProtectedCommittedDraft? {
        authorize(projectId, binding)
        val area = area(binding)
        val pointer = synchronized(lock) {
            checkBinding(binding)
            val current = readPointer(binding, area)
            if (current != null) verifiedGeneration(binding, area, current)
            recoverCandidates(binding, area, current)
            current ?: return null
        }
        val evidence = ProtectedNativeRepeatImageProbe(
            scratch, File(area, pointer.generation), settings
        ).inspectCombined(projectId, binding, true)
        synchronized(lock) {
            require(readPointer(binding, area) == pointer) { "Committed draft changed during reopen" }
            verifiedGeneration(binding, area, pointer)
        }
        return ProtectedCommittedDraft(pointer.generation, evidence)
    }

    /**
     * Check exact current generation, fully populated native questionnaire,
     * and absence of newer working edits. No serialization or export.
     */
    fun verifyFinalizationEligibility(
        projectId: String, binding: QuarantinedWorkDraft, expectedGeneration: String
    ): ProtectedFinalizationEligibility = inspectFinalization(projectId, binding, expectedGeneration, null)

    fun verifyNativeXmlSerialization(
        projectId: String, binding: QuarantinedWorkDraft, expectedGeneration: String
    ): ProtectedCommittedXmlEvidence {
        var xml: ProtectedNativeXmlEvidence? = null
        val eligibility = inspectFinalization(projectId, binding, expectedGeneration) {
            check(xml == null)
            xml = it
        }
        return ProtectedCommittedXmlEvidence(eligibility, requireNotNull(xml))
    }

    private fun inspectFinalization(
        projectId: String, binding: QuarantinedWorkDraft, expectedGeneration: String,
        onSerialized: ((ProtectedNativeXmlEvidence) -> Unit)?
    ): ProtectedFinalizationEligibility {
        authorize(projectId, binding)
        val area = area(binding)
        fun checkCurrent(): Pointer {
            checkBinding(binding)
            val current = requireNotNull(readPointer(binding, area))
            require(current.generation == expectedGeneration) { "Stale committed generation" }
            verifiedGeneration(binding, area, current)
            require(inventoryDigest(inventory(work(root, binding))) == current.inventoryDigest) {
                "Uncommitted working changes must be published first"
            }
            return current
        }
        val pointer = synchronized(lock) { checkCurrent() }
        val evidence = ProtectedNativeRepeatImageProbe(
            scratch, File(area, pointer.generation), settings
        ).inspectCombined(projectId, binding, allowMixedDirect = true, requireComplete = true,
            onSerialized = onSerialized)
        authorize(projectId, binding)
        synchronized(lock) { require(checkCurrent() == pointer) }
        return ProtectedFinalizationEligibility(binding.workId, pointer.generation,
            binding.reservedInstanceId, binding.targetFormVersionId, pointer.inventoryDigest, evidence)
    }

    private fun candidateMarker(area: File, id: String): File {
        require(UUID.fromString(id).toString() == id)
        return File(area, ".candidate-" + id + ".aesgcm")
    }

    private fun markerAad(b: QuarantinedWorkDraft, id: String): ByteArray =
        aad(b) + ("\nDC_UNPUBLISHED_CANDIDATE_V1\n" + id).toByteArray(Charsets.UTF_8)

    private fun writeCandidateMarker(b: QuarantinedWorkDraft, area: File, id: String) {
        val marker = candidateMarker(area, id)
        val pending = File(area, ".marker-write-" + id)
        require(!marker.exists() && !pending.exists())
        try {
            val sealed = cipher.seal(id.toByteArray(Charsets.UTF_8), markerAad(b, id))
            FileOutputStream(pending).use { it.write(sealed); it.fd.sync() }
            require(pending.renameTo(marker))
        } finally {
            if (pending.exists()) pending.delete()
        }
    }

    /** Called only under the global component monitor, after pointer verification. */
    private fun recoverCandidates(b: QuarantinedWorkDraft, area: File, current: Pointer?) {
        for (marker in requireNotNull(area.listFiles())) {
            if (!marker.name.startsWith(".candidate-")) continue
            require(marker.name.endsWith(".aesgcm") && marker.isFile &&
                !java.nio.file.Files.isSymbolicLink(marker.toPath()) &&
                marker.length() in 30..1024L)
            val id = marker.name.removePrefix(".candidate-").removeSuffix(".aesgcm")
            require(candidateMarker(area, id) == marker)
            val candidate = File(area, id)
            if (id != current?.generation && candidate.absolutePath in activeCandidates) continue
            val clear = cipher.open(marker.readBytes(), markerAad(b, id))
            try { require(String(clear, Charsets.UTF_8) == id) }
            finally { clear.fill(0) }
            if (id != current?.generation) removeCandidateTree(b, candidate)
            check(marker.delete()) { "Abandoned generation marker could not be removed" }
        }
    }

    /** Only a marked, unpublished generation may be removed. Never traverse links. */
    private fun removeCandidateTree(b: QuarantinedWorkDraft, candidate: File) {
        if (!candidate.exists()) {
            require(!java.nio.file.Files.isSymbolicLink(candidate.toPath()))
            return
        }
        val expectedDirs = setOf(candidate,
            File(candidate, QuarantinedWorkDraftVault.VAULT_DIRECTORY),
            File(File(candidate, QuarantinedWorkDraftVault.VAULT_DIRECTORY), b.projectId),
            work(candidate, b))
        val leafDir = work(candidate, b)
        val nodes = candidate.walkTopDown().onEnter { dir ->
            require(dir in expectedDirs && dir.canonicalFile == dir.absoluteFile &&
                !java.nio.file.Files.isSymbolicLink(dir.toPath()))
            true
        }.toList()
        require(nodes.size <= MAX_FILES + 4)
        for (node in nodes) {
            require(!java.nio.file.Files.isSymbolicLink(node.toPath()) &&
                (node in expectedDirs && node.isDirectory ||
                    node.parentFile == leafDir && node.isFile && ALLOWED.matches(node.name)))
        }
        check(candidate.deleteRecursively()) { "Unable to remove abandoned generation" }
    }

    private fun authorize(projectId: String, binding: QuarantinedWorkDraft) {
        require(projectId == binding.projectId && binding.workType == RecordWorkType.FOLLOW_UP &&
            binding.validationTransfers == 0 && !binding.canOpenEditor &&
            !binding.canFinalize && !binding.canUpload)
        checkBinding(binding)
        RecordWorkPreparationClient(settings).authorizePrivateTextForm(projectId, binding)
    }

    private fun checkBinding(binding: QuarantinedWorkDraft) {
        require(QuarantinedWorkDraftVault(root).read(
            binding.projectId, binding.workId, binding.actorContextSha256) == binding)
    }

    private fun area(binding: QuarantinedWorkDraft): File {
        val dir = File(File(File(root.canonicalFile, "recordwork-committed"), binding.projectId), binding.workId)
        synchronized(lock) {
            require(dir.isDirectory || dir.mkdirs())
            require(dir.canonicalFile == File(root.canonicalFile,
                "recordwork-committed/${binding.projectId}/${binding.workId}"))
        }
        return dir
    }

    private fun work(base: File, b: QuarantinedWorkDraft) =
        File(File(File(base.canonicalFile, QuarantinedWorkDraftVault.VAULT_DIRECTORY), b.projectId), b.workId)

    private fun inventory(dir: File): Map<String, String> {
        require(dir.isDirectory && dir.canonicalFile == dir.absoluteFile)
        val files = requireNotNull(dir.listFiles()).sortedBy { it.name }
        require(files.size in 2..MAX_FILES)
        var total = 0L
        val hashes = linkedMapOf<String, String>()
        for (file in files) {
            require(file.isFile && !java.nio.file.Files.isSymbolicLink(file.toPath()) &&
                ALLOWED.matches(file.name) && file.length() in 1..2_200_000L)
            total += file.length()
            require(total <= MAX_BYTES)
            hashes[file.name] = QuarantinedPrivateImageStore.digest(file.readBytes())
        }
        require("binding.properties" in hashes && "mapping-approval.aesgcm" in hashes)
        return hashes
    }

    private fun inventoryDigest(hashes: Map<String, String>) =
        QuarantinedWorkDraftVault.sha256(hashes.toSortedMap().entries.joinToString("\n") {
            it.key + "=" + it.value
        })

    private fun verifiedGeneration(b: QuarantinedWorkDraft, area: File, p: Pointer) {
        require(UUID.fromString(p.generation).toString() == p.generation)
        val dir = File(area, p.generation)
        require(dir.isDirectory && dir.canonicalFile == dir.absoluteFile)
        require(inventoryDigest(inventory(work(dir, b))) == p.inventoryDigest) {
            "Committed generation ciphertext inventory changed"
        }
    }

    private fun readPointer(b: QuarantinedWorkDraft, area: File): Pointer? {
        val file = File(area, "current.aesgcm")
        if (!file.exists()) return null
        require(file.isFile && !java.nio.file.Files.isSymbolicLink(file.toPath()) &&
            file.length() in 30..1024L)
        val clear = cipher.open(file.readBytes(), aad(b))
        try {
            val parts = String(clear, Charsets.UTF_8).split("\n")
            require(parts.size == 2 && UUID.fromString(parts[0]).toString() == parts[0] &&
                parts[1].matches(Regex("[0-9a-f]{64}")))
            return Pointer(parts[0], parts[1])
        } finally { clear.fill(0) }
    }

    private fun writePointer(b: QuarantinedWorkDraft, area: File, p: Pointer) {
        val bytes = (p.generation + "\n" + p.inventoryDigest).toByteArray(Charsets.UTF_8)
        val pending = File(area, ".publish-" + UUID.randomUUID())
        try {
            val sealed = cipher.seal(bytes, aad(b))
            FileOutputStream(pending).use {
                it.write(sealed)
                it.fd.sync()
            }
            require(pending.renameTo(File(area, "current.aesgcm"))) { "Atomic publication failed" }
        } finally {
            bytes.fill(0)
            if (pending.exists()) pending.delete()
        }
    }

    private fun aad(b: QuarantinedWorkDraft) = listOf(
        "DC_COMMITTED_DRAFT_V1", b.projectId, b.actorContextSha256, b.workId,
        b.reservedInstanceId, b.sourceSubmissionId, b.sourceXmlSha256,
        b.targetFormVersionId, b.targetXFormSha256, b.workType.name, b.visitKey.orEmpty()
    ).joinToString("\n").toByteArray(Charsets.UTF_8)

    companion object {
        private val activeCandidates = mutableSetOf<String>()
        private const val MAX_FILES = 256
        private const val MAX_BYTES = 64L * 1024 * 1024
        private const val MAX_GENERATIONS = 8
        private val ALLOWED = Regex(
            """binding\.properties|mapping-approval\.aesgcm|private-answers\.aesgcm|repeat-row-identities\.aesgcm|image-[0-9a-f]{32}\.aesgcm|repeat-answer-[0-9a-f-]{36}\.aesgcm|repeat-image-[0-9a-f-]{36}-[0-9a-f]{32}\.aesgcm"""
        )
    }
}
