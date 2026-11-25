package org.odk.collect.android.recordwork

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.security.MessageDigest

/**
 * Stores a single, explicitly selected, NEW image per authorized current
 * target-version image field. An opaque encrypted asset is NOT an ODK answer,
 * and the store provides no export, finalize, submit or old-attachment copying.
 *
 * The Activity must fetch READY work and prove field membership with a new
 * JavaRosaPrivateImageFieldProbe before every import/read/clear. This store
 * checks an unchanged persisted local binding as defense in depth.
 */
data class ProtectedImageReceipt(
    val fieldPath: String,
    val mime: String,
    val bytes: Int,
    val sha256: String,
    val canFinalize: Boolean = false,
    val canSubmit: Boolean = false
) {
    init {
        require(!canFinalize && !canSubmit) {
            "Staged image is not an uploadable instance attachment"
        }
    }
}

class QuarantinedPrivateImageStore(
    private val noBackupRoot: File,
    private val cipher: PrivateDraftCipher,
    private val vault: QuarantinedWorkDraftVault =
        QuarantinedWorkDraftVault(noBackupRoot)
) {
    /**
     * Input is a content URI stream, never a filesystem path or a persisted
     * source-record attachment. The stream is bounded before encryption.
     * Existing ciphertext remains untouched when any check fails.
     */
    fun importSelectedImage(
        binding: QuarantinedWorkDraft,
        fieldPath: String,
        permittedFields: List<String>,
        claimedMime: String,
        source: InputStream
    ): ProtectedImageReceipt = synchronized(ProtectedDraftComponentLock.monitor) {
        requireAllowed(binding, fieldPath, permittedFields)
        require(claimedMime in MIME_TYPES) { "Unsupported private image MIME" }
        val clear = readBounded(source)
        try {
            require(sniffMime(clear) == claimedMime) {
                "Image signature differs from claimed MIME"
            }
            val receipt = ProtectedImageReceipt(
                fieldPath, claimedMime, clear.size, digest(clear)
            )
            val bytes = serialize(receipt, clear)
            try {
                val ciphertext = cipher.seal(bytes, aad(binding, fieldPath))
                require(ciphertext.size in 30..MAX_CIPHER_FILE) {
                    "Encrypted private image is oversized"
                }
                val target = slot(binding, fieldPath)
                require(target.parentFile?.isDirectory == true &&
                    !java.nio.file.Files.isSymbolicLink(target.parentFile.toPath()) &&
                    !java.nio.file.Files.isSymbolicLink(target.toPath())) {
                    "Unsafe private image target"
                }
                val pending = File(target.parentFile,
                    ".private-image-" + java.util.UUID.randomUUID())
                try {
                    FileOutputStream(pending).use { out ->
                        out.write(ciphertext)
                        out.fd.sync()
                    }
                    // A failed atomic replacement cannot delete the prior
                    // protected answer. Never use a delete-first fallback.
                    require(pending.renameTo(target)) {
                        "Protected image atomic replacement failed"
                    }
                } finally {
                    if (pending.exists()) pending.delete()
                }
                receipt
            } finally {
                bytes.fill(0)
            }
        } finally {
            clear.fill(0)
        }
    }

    /**
     * Decrypts, authenticates, verifies byte hash and magic, invokes the
     * consumer synchronously, then zeroes the internal byte buffer. Do not
     * retain or log the image bytes in callers.
     */
    fun <T> withVerifiedImage(
        binding: QuarantinedWorkDraft,
        fieldPath: String,
        permittedFields: List<String>,
        consume: (ProtectedImageReceipt, ByteArray) -> T
    ): T? = synchronized(ProtectedDraftComponentLock.monitor) {
        requireAllowed(binding, fieldPath, permittedFields)
        val file = slot(binding, fieldPath)
        if (!file.exists()) return@synchronized null
        require(file.isFile && !java.nio.file.Files.isSymbolicLink(file.toPath()) &&
            file.length() in 30..MAX_CIPHER_FILE.toLong()) {
            "Corrupt protected image slot"
        }
        val clear = cipher.open(file.readBytes(), aad(binding, fieldPath))
        try {
            val (receipt, image) = deserialize(clear, fieldPath)
            try {
                consume(receipt, image)
            } finally {
                image.fill(0)
            }
        } finally {
            clear.fill(0)
        }
    }

    fun receipt(
        binding: QuarantinedWorkDraft,
        fieldPath: String,
        permittedFields: List<String>
    ): ProtectedImageReceipt? =
        withVerifiedImage(binding, fieldPath, permittedFields) { receipt, _ -> receipt }

    fun clear(
        binding: QuarantinedWorkDraft,
        fieldPath: String,
        permittedFields: List<String>
    ) = synchronized(ProtectedDraftComponentLock.monitor) {
        requireAllowed(binding, fieldPath, permittedFields)
        val file = slot(binding, fieldPath)
        if (file.exists()) {
            require(file.isFile && !java.nio.file.Files.isSymbolicLink(file.toPath()) &&
                file.delete()) {
                "Unable to clear private staged image"
            }
        }
    }

    private fun requireAllowed(
        binding: QuarantinedWorkDraft,
        fieldPath: String,
        permittedFields: List<String>
    ) {
        val active = requireNotNull(vault.read(
            binding.projectId, binding.workId, binding.actorContextSha256
        )) { "No authorized local work-item binding" }
        require(active == binding &&
            binding.state == QuarantinedWorkDraft.STATE_QUARANTINED &&
            !binding.canFinalize && !binding.canUpload &&
            permittedFields.isNotEmpty() &&
            permittedFields.size <= JavaRosaPrivateImageFieldProbe.MAX_IMAGES &&
            permittedFields.toSet().size == permittedFields.size &&
            fieldPath in permittedFields &&
            FIELD_PATH.matches(fieldPath)) {
            "Image field is not authorized for this work"
        }
    }

    private fun slot(binding: QuarantinedWorkDraft, path: String): File {
        val dir = File(
            File(
                File(noBackupRoot, QuarantinedWorkDraftVault.VAULT_DIRECTORY),
                binding.projectId
            ), binding.workId
        )
        require(dir.canonicalFile.parentFile?.name == binding.projectId &&
            dir.canonicalFile.parentFile?.parentFile?.name ==
                QuarantinedWorkDraftVault.VAULT_DIRECTORY &&
            dir.isDirectory && !java.nio.file.Files.isSymbolicLink(dir.toPath())) {
            "Protected asset path escaped its work item"
        }
        return File(dir, "image-" + digest(path.toByteArray()).take(32) + ".aesgcm")
    }

    private fun readBounded(stream: InputStream): ByteArray {
        ByteArrayOutputStream().use { output ->
            stream.use { input ->
                val buffer = ByteArray(8192)
                var total = 0
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (count == 0) continue
                    total += count
                    require(total <= MAX_IMAGE_BYTES) {
                        "Selected image exceeds private staging limit"
                    }
                    output.write(buffer, 0, count)
                }
            }
            require(totalIsValid(output.size())) { "Empty/invalid private image" }
            return output.toByteArray()
        }
    }

    private fun totalIsValid(size: Int) = size in 12..MAX_IMAGE_BYTES

    private fun serialize(
        receipt: ProtectedImageReceipt,
        image: ByteArray
    ): ByteArray {
        val data = ByteArrayOutputStream()
        DataOutputStream(data).use { out ->
            out.writeInt(FORMAT)
            out.writeUTF(receipt.fieldPath)
            out.writeUTF(receipt.mime)
            out.writeUTF(receipt.sha256)
            out.writeInt(receipt.bytes)
            out.write(image)
        }
        return data.toByteArray()
    }

    private fun deserialize(
        clear: ByteArray,
        expectedPath: String
    ): Pair<ProtectedImageReceipt, ByteArray> {
        require(clear.size in 105..MAX_CIPHER_FILE)
        val input = DataInputStream(ByteArrayInputStream(clear))
        require(input.readInt() == FORMAT)
        val path = input.readUTF()
        val mime = input.readUTF()
        val recordedHash = input.readUTF()
        val count = input.readInt()
        require(path == expectedPath && mime in MIME_TYPES &&
            HASH.matches(recordedHash) &&
            count in 12..MAX_IMAGE_BYTES && input.available() == count) {
            "Protected image metadata does not match its native field"
        }
        val bytes = ByteArray(count)
        input.readFully(bytes)
        require(sniffMime(bytes) == mime &&
            digest(bytes) == recordedHash &&
            input.available() == 0) {
            "Private image bytes failed signature/hash validation"
        }
        return ProtectedImageReceipt(path, mime, count, recordedHash) to bytes
    }

    private fun aad(binding: QuarantinedWorkDraft, path: String): ByteArray =
        listOf(
            "DC_PRIVATE_IMAGE_V1",
            binding.projectId, binding.actorContextSha256, binding.workId,
            binding.sourceSubmissionId, binding.sourceInstanceId,
            binding.targetFormVersionId, binding.targetFormId,
            binding.targetVersion, binding.workType.name, binding.visitKey.orEmpty(),
            binding.reservedInstanceId, binding.sourceXmlSha256,
            binding.sourceXFormSha256.orEmpty(), binding.targetXFormSha256,
            binding.reviewedPaths.toString(), binding.validationTransfers.toString(),
            binding.requiresDeprecatedId.toString(), path
        ).joinToString("\n").toByteArray(Charsets.UTF_8)

    companion object {
        const val MAX_IMAGE_BYTES = 1_500_000
        private const val MAX_CIPHER_FILE = 1_600_000
        private const val FORMAT = 1
        private val FIELD_PATH = Regex(
            "/[A-Za-z_][A-Za-z0-9_.-]*(/[A-Za-z_][A-Za-z0-9_.-]*){1,11}"
        )
        private val HASH = Regex("[0-9a-f]{64}")
        private val MIME_TYPES = setOf("image/jpeg", "image/png", "image/webp")

        fun sniffMime(data: ByteArray): String? =
            when {
                data.size >= 3 &&
                    (data[0].toInt() and 255) == 0xff &&
                    (data[1].toInt() and 255) == 0xd8 &&
                    (data[2].toInt() and 255) == 0xff -> "image/jpeg"
                data.size >= 8 &&
                    data.copyOfRange(0, 8).contentEquals(
                        byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47,
                            0x0d, 0x0a, 0x1a, 0x0a)
                    ) -> "image/png"
                data.size >= 12 &&
                    String(data.copyOfRange(0, 4), Charsets.US_ASCII) == "RIFF" &&
                    String(data.copyOfRange(8, 12), Charsets.US_ASCII) == "WEBP" ->
                    "image/webp"
                else -> null
            }

        fun digest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
            .digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}
