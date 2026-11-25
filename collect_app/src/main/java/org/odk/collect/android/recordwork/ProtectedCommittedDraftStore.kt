package org.odk.collect.android.recordwork

import org.odk.collect.settings.SettingsProvider
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

internal data class ProtectedCommittedDraft(
    val generation: String,
    val evidence: ProtectedCombinedRepeatEvidence
)

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
        try {
            val captured = synchronized(lock) {
                checkBinding(binding)
                val previous = readPointer(binding, area)
                require(previous?.generation == expectedGeneration) { "Stale draft generation" }
                if (previous != null) verifiedGeneration(binding, area, previous)
                require(area.listFiles().orEmpty().count { it.isDirectory } < MAX_GENERATIONS) {
                    "Committed generation storage limit reached"
                }
                require(candidate.mkdir())
                val source = work(root, binding)
                val inventory = inventory(source)
                val target = work(candidate, binding)
                require(target.mkdirs())
                for (name in inventory.keys) {
                    val bytes = File(source, name).readBytes()
                    require(QuarantinedPrivateImageStore.digest(bytes) == inventory[name])
                    FileOutputStream(File(target, name)).use {
                        it.write(bytes)
                        it.fd.sync()
                    }
                }
                require(inventory(target) == inventory)
                inventory
            }
            // Authorization and native reconstruction run outside the lock.
            val evidence = ProtectedNativeRepeatImageProbe(scratch, candidate, settings)
                .inspectCombined(projectId, binding, true)
            authorize(projectId, binding)
            beforePublish()
            synchronized(lock) {
                checkBinding(binding)
                require(readPointer(binding, area)?.generation == expectedGeneration)
                require(inventory(work(root, binding)) == captured) {
                    "Working draft changed during validation"
                }
                require(inventory(work(candidate, binding)) == captured)
                val pointer = Pointer(id, inventoryDigest(captured))
                writePointer(binding, area, pointer)
                published = true
            }
            return ProtectedCommittedDraft(id, evidence)
        } finally {
            if (!published && candidate.exists()) candidate.deleteRecursively()
        }
    }

    fun reopen(projectId: String, binding: QuarantinedWorkDraft): ProtectedCommittedDraft? {
        authorize(projectId, binding)
        val area = area(binding)
        val pointer = synchronized(lock) {
            checkBinding(binding)
            val current = readPointer(binding, area) ?: return null
            verifiedGeneration(binding, area, current)
            current
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
        val dir = File(File(File(root, "recordwork-committed"), binding.projectId), binding.workId)
        synchronized(lock) {
            require(dir.isDirectory || dir.mkdirs())
            require(dir.canonicalFile == File(root.canonicalFile,
                "recordwork-committed/${binding.projectId}/${binding.workId}"))
        }
        return dir
    }

    private fun work(base: File, b: QuarantinedWorkDraft) =
        File(File(File(base, QuarantinedWorkDraftVault.VAULT_DIRECTORY), b.projectId), b.workId)

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
        private const val MAX_FILES = 256
        private const val MAX_BYTES = 64L * 1024 * 1024
        private const val MAX_GENERATIONS = 8
        private val ALLOWED = Regex(
            """binding\.properties|mapping-approval\.aesgcm|private-answers\.aesgcm|repeat-row-identities\.aesgcm|image-[0-9a-f]{32}\.aesgcm|repeat-answer-[0-9a-f-]{36}\.aesgcm|repeat-image-[0-9a-f-]{36}-[0-9a-f]{32}\.aesgcm"""
        )
    }
}
