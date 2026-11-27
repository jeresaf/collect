package org.odk.collect.android.recordwork

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.util.UUID

internal data class ProtectedPersistedPackage(
    val evidence: ProtectedCommittedPackageEvidence,
    val ciphertextSha256: String,
    val canUpload: Boolean = false
) {
    init { require(!canUpload) }
}

/**
 * Caller holds ProtectedDraftComponentLock and has revalidated the exact
 * current/working inventory and live READY work. No standard ODK instance,
 * plaintext file, upload permission or external file path is produced.
 * One immutable envelope per generation; a corrupt envelope is never
 * overwritten. An unpublished encrypted temp is never accepted as final.
 */
internal class ProtectedFinalizedPackageFile(
    private val area: File,
    private val binding: QuarantinedWorkDraft,
    private val generation: String,
    inventoryDigest: String
) {
    private val cipher = AndroidKeyStorePrivateDraftCipher(MAX_ENVELOPE)
    private val aad = listOf("DC_FINALIZED_PACKAGE_V1", binding.projectId,
        binding.actorContextSha256, binding.workId, binding.sourceSubmissionId,
        binding.sourceInstanceId, binding.sourceXmlSha256, binding.targetFormVersionId,
        binding.targetXFormSha256, binding.reservedInstanceId, binding.workType.name,
        binding.visitKey.orEmpty(), generation, inventoryDigest).joinToString("\n").toByteArray(Charsets.UTF_8)

    init {
        require(UUID.fromString(generation).toString() == generation &&
            inventoryDigest.matches(Regex("[0-9a-f]{64}")))
        require(area.isDirectory && area.canonicalFile == area.absoluteFile)
    }

    fun verifyOrCreate(expected: ByteArray, allowCreate: Boolean): String {
        require(expected.size in 1..MAX_ENVELOPE - 30)
        val target = File(area, "finalized-" + generation + ".aesgcm")
        val pending = File(area, ".finalized-write-" + generation)
        require(!Files.isSymbolicLink(target.toPath()) && !Files.isSymbolicLink(pending.toPath()))
        if (target.exists()) {
            val sha = verify(target, expected)
            if (pending.exists()) removePending(pending)
            return sha
        }
        // A missing published envelope is never reconstructed on reopen.
        require(allowCreate) { "Finalized package has not been published" }
        if (pending.exists()) removePending(pending)
        try {
            val sealed = cipher.seal(expected, aad)
            require(sealed.size in 30..MAX_ENVELOPE)
            FileOutputStream(pending).use { out ->
                out.write(sealed)
                out.fd.sync()
            }
            val sha = verify(pending, expected)
            require(!target.exists() && !Files.isSymbolicLink(target.toPath()))
            require(pending.renameTo(target)) { "Atomic finalized package publication failed" }
            require(verify(target, expected) == sha)
            return sha
        } finally {
            if (pending.exists()) removePending(pending)
        }
    }

    // Only the dedicated sender uses the decrypted published bytes, after
    // exact canonical comparison and fresh current-generation authorization.
    fun withVerifiedContent(expected: ByteArray, expectedCiphertextSha: String,
                            consume: (ByteArray) -> Unit) {
        val target = File(area, "finalized-" + generation + ".aesgcm")
        require(target.isFile && target.canonicalFile == target.absoluteFile &&
            !Files.isSymbolicLink(target.toPath()) && target.length() in 30..MAX_ENVELOPE.toLong())
        val envelope = target.readBytes()
        require(QuarantinedPrivateImageStore.digest(envelope) == expectedCiphertextSha)
        val clear = cipher.open(envelope, aad)
        try {
            require(java.security.MessageDigest.isEqual(clear, expected))
            consume(clear)
        } finally { clear.fill(0) }
    }

    /** Authenticate the exact sealed bytes after an attempt; no repair or creation. */
    internal fun inspectAttempted(attempt: ProtectedSubmittedReceipt,
                                  consume: ((ByteArray) -> Unit)? = null): ProtectedAttemptedPackageEvidence {
        require(attempt.generation == generation)
        val target = File(area, "finalized-" + generation + ".aesgcm")
        require(target.isFile && target.canonicalFile == target.absoluteFile &&
            !Files.isSymbolicLink(target.toPath()) && target.length() in 30..MAX_ENVELOPE.toLong())
        val envelope = target.readBytes()
        require(QuarantinedPrivateImageStore.digest(envelope) == attempt.ciphertextSha256)
        val clear = cipher.open(envelope, aad)
        try {
            val evidence = ProtectedAttemptedPackageEvidence.parse(clear, binding, attempt)
            consume?.invoke(clear)
            return evidence
        } finally { clear.fill(0) }
    }

    private fun verify(file: File, expected: ByteArray): String {
        require(file.isFile && file.canonicalFile == file.absoluteFile &&
            !Files.isSymbolicLink(file.toPath()) && file.length() in 30..MAX_ENVELOPE.toLong())
        val envelope = file.readBytes()
        val clear = cipher.open(envelope, aad)
        try {
            require(java.security.MessageDigest.isEqual(clear, expected)) {
                "Finalized package differs from the authorized committed contents"
            }
            return QuarantinedPrivateImageStore.digest(envelope)
        } finally { clear.fill(0) }
    }

    private fun removePending(file: File) {
        require(file.isFile && !Files.isSymbolicLink(file.toPath()) &&
            file.canonicalFile == file.absoluteFile && file.length() in 0..MAX_ENVELOPE.toLong())
        check(file.delete()) { "Unable to remove unpublished finalized envelope" }
    }

    companion object { private const val MAX_ENVELOPE = 13 * 1024 * 1024 }
}
