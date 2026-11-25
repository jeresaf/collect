package org.odk.collect.android.recordwork

import java.io.Closeable
import java.io.DataOutputStream
import java.io.OutputStream
import java.security.DigestOutputStream
import java.security.MessageDigest
import java.util.UUID

internal data class ProtectedAttachmentEvidence(
    val filename: String,
    val mime: String,
    val bytes: Int,
    val sha256: String
)

internal data class ProtectedNativePackageEvidence(
    val xml: ProtectedNativeXmlEvidence,
    val attachments: Map<Pair<String, String>, ProtectedAttachmentEvidence>,
    val packageSha256: String,
    val totalContentBytes: Long
)

internal data class ProtectedCommittedPackageEvidence(
    val eligibility: ProtectedFinalizationEligibility,
    val packaged: ProtectedNativePackageEvidence
)

/**
 * Bounded in-memory package of actual authenticated XML and media bytes.
 * The canonical framed byte stream is hashed, not persisted or transmitted.
 * No paths, attachment files, standard ODK instances or upload credentials.
 */
internal class ProtectedNativePackageBuilder(
    private val binding: QuarantinedWorkDraft,
    private val generation: String
) : Closeable {
    private data class Part(val evidence: ProtectedAttachmentEvidence, val content: ByteArray)
    private val parts = linkedMapOf<Pair<String, String>, Part>()
    private var mediaBytes = 0L
    private var closed = false
    private var finished = false

    init {
        require(UUID.fromString(generation).toString() == generation)
    }

    fun add(slot: Pair<String, String>, mime: String, count: Int, sha: String, bytes: ByteArray) {
        check(!closed && !finished)
        require(slot !in parts && parts.size < 64)
        require(count == bytes.size && count in 1..1_500_000)
        require(QuarantinedPrivateImageStore.sniffMime(bytes) == mime &&
            QuarantinedPrivateImageStore.digest(bytes) == sha)
        require(mediaBytes + count <= MAX_CONTENT)
        val name = filename(binding, slot, mime, sha)
        require(parts.values.none { it.evidence.filename == name })
        parts[slot] = Part(ProtectedAttachmentEvidence(name, mime, count, sha), bytes.copyOf())
        mediaBytes += count
    }

    fun finish(xml: ProtectedNativeXmlEvidence, bytes: ByteArray,
               onCanonical: ((ByteArray) -> Unit)? = null): ProtectedNativePackageEvidence {
        check(!closed && !finished)
        require(bytes.size == xml.xmlBytes && bytes.isNotEmpty() &&
            QuarantinedPrivateImageStore.digest(bytes) == xml.xmlSha256)
        require(mediaBytes + bytes.size <= MAX_CONTENT)
        require(parts.isNotEmpty() && parts.keys == xml.mediaReferenceDigests.keys)
        require(parts.all { (key, part) ->
            xml.mediaReferenceDigests[key] ==
                QuarantinedWorkDraftVault.sha256(part.evidence.filename) &&
                QuarantinedPrivateImageStore.digest(part.content) == part.evidence.sha256 &&
                QuarantinedPrivateImageStore.sniffMime(part.content) == part.evidence.mime &&
                part.content.size == part.evidence.bytes
        })
        val digest = MessageDigest.getInstance("SHA-256")
        // No transport-specific multipart boundary or temporary cleartext
        // files. Length framing and stable name ordering define this proof.
        val buffer = if (onCanonical == null) null else object : java.io.ByteArrayOutputStream() {
            fun wipe() { buf.fill(0); reset() }
        }
        val sink = object : OutputStream() {
            override fun write(b: Int) { buffer?.write(b) }
            override fun write(b: ByteArray, off: Int, len: Int) { buffer?.write(b, off, len) }
        }
        try {
            DataOutputStream(DigestOutputStream(sink, digest)).use { out ->
            out.writeUTF("DC_NATIVE_PACKAGE_V1")
            out.writeUTF(binding.projectId)
            out.writeUTF(binding.actorContextSha256)
            out.writeUTF(binding.workId)
            out.writeUTF(binding.sourceSubmissionId)
            out.writeUTF(binding.targetFormVersionId)
            out.writeUTF(binding.reservedInstanceId)
            out.writeUTF(generation)
            out.writeInt(bytes.size)
            out.write(bytes)
            out.writeInt(parts.size)
            for ((key, part) in parts.entries.sortedBy { it.value.evidence.filename }) {
                out.writeUTF(key.first)
                out.writeUTF(key.second)
                out.writeUTF(part.evidence.filename)
                out.writeUTF(part.evidence.mime)
                out.writeInt(part.content.size)
                out.write(part.content)
            }
            }
            buffer?.let {
                val canonical = it.toByteArray()
                try {
                    require(canonical.size <= 13 * 1024 * 1024 - 30)
                    requireNotNull(onCanonical).invoke(canonical)
                } finally { canonical.fill(0) }
            }
        } finally { buffer?.wipe() }
        finished = true
        val sha = digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
        return ProtectedNativePackageEvidence(xml, parts.mapValues { it.value.evidence },
            sha, mediaBytes + bytes.size)
    }

    override fun close() {
        parts.values.forEach { it.content.fill(0) }
        parts.clear()
        mediaBytes = 0
        closed = true
    }

    companion object {
        private const val MAX_CONTENT = 12L * 1024 * 1024
        fun filename(binding: QuarantinedWorkDraft, slot: Pair<String, String>,
                     mime: String, sha: String): String {
            require(slot.first == "direct" || UUID.fromString(slot.first).toString() == slot.first)
            require(slot.second.matches(Regex("/[A-Za-z_][A-Za-z0-9_.-]*(/[A-Za-z_][A-Za-z0-9_.-]*){1,2}")))
            require(sha.matches(Regex("[0-9a-f]{64}")))
            val extension = when (mime) {
                "image/jpeg" -> "jpg"
                "image/png" -> "png"
                "image/webp" -> "webp"
                else -> throw IllegalArgumentException("Unsupported package MIME")
            }
            return "protected-" + QuarantinedWorkDraftVault.sha256(
                binding.reservedInstanceId + "\n" + slot.first + "\n" + slot.second + "\n" + sha
            ) + "." + extension
        }
    }
}
