package org.odk.collect.android.recordwork

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.UUID

/**
 * A NEW, manually selected, encrypted image belongs to exactly one
 * permanent repeat-row UUID AND one native-reviewed image question.
 * It is NOT a JavaRosa binary answer, an ODK Instance, or an attachment
 * eligible for submission. Old visit attachments are never imported.
 *
 * Caller performs authenticated server READY authorization before each
 * operation and supplies a freshly probed JavaRosa repeat session.
 * Row create/archive, image read/import/clear share one process-local lock.
 */
data class ProtectedRepeatImageReceipt(
    val rowId: String,
    val fieldPath: String,
    val mime: String,
    val bytes: Int,
    val sha256: String,
    val canFinalize: Boolean = false,
    val canUpload: Boolean = false
) {
    init {
        require(!canFinalize && !canUpload) {
            "Private repeat media cannot finalize or upload an ODK instance"
        }
    }
}

class QuarantinedRepeatImageStore(
    private val root: File,
    private val cipher: PrivateDraftCipher,
    private val rows: QuarantinedRepeatRowVault =
        QuarantinedRepeatRowVault(root, cipher)
) {
    fun importSelectedImage(
        binding: QuarantinedWorkDraft,
        session: JavaRosaPrivateRepeatAnswerSession,
        rowId: String,
        fieldPath: String,
        mime: String,
        source: InputStream
    ): ProtectedRepeatImageReceipt =
        rows.withLockedCurrentInventory(binding, session.schema) { inventory ->
            val row = requireAllowed(inventory, session.schema, rowId, fieldPath)
            require(mime in SUPPORTED) { "Selected repeat image type is unsupported" }
            val data = boundedRead(source)
            try {
                require(QuarantinedPrivateImageStore.sniffMime(data) == mime) {
                    "Repeat image file signature differs from claimed MIME"
                }
                val receipt = ProtectedRepeatImageReceipt(
                    rowId, fieldPath, mime, data.size,
                    QuarantinedPrivateImageStore.digest(data)
                )
                val plain = encode(receipt, row, data)
                try {
                    val encrypted = cipher.seal(
                        plain, associatedData(binding, session.schema, row, fieldPath)
                    )
                    require(encrypted.size in 30..MAX_ENCRYPTED)
                    val file = fileFor(binding, row, fieldPath)
                    val pending = File(file.parentFile,
                        ".private-repeat-image-" + UUID.randomUUID())
                    try {
                        FileOutputStream(pending).use { out ->
                            out.write(encrypted)
                            out.fd.sync()
                        }
                        // Never delete existing ciphertext to make room.
                        require(pending.renameTo(file)) {
                            "Atomic protected repeat image replacement failed"
                        }
                    } finally {
                        if (pending.exists()) pending.delete()
                    }
                    receipt
                } finally {
                    plain.fill(0)
                }
            } finally {
                data.fill(0)
            }
        }

    fun <T> withVerifiedImage(
        binding: QuarantinedWorkDraft,
        session: JavaRosaPrivateRepeatAnswerSession,
        rowId: String,
        fieldPath: String,
        consume: (ProtectedRepeatImageReceipt, ByteArray) -> T
    ): T? =
        rows.withLockedCurrentInventory(binding, session.schema) { inventory ->
            val row = requireAllowed(inventory, session.schema, rowId, fieldPath)
            val file = fileFor(binding, row, fieldPath)
            if (!file.exists()) return@withLockedCurrentInventory null
            require(file.isFile &&
                !java.nio.file.Files.isSymbolicLink(file.toPath()) &&
                file.length() in 30..MAX_ENCRYPTED.toLong()) {
                "Invalid private repeat image slot"
            }
            val plain = cipher.open(
                file.readBytes(),
                associatedData(binding, session.schema, row, fieldPath)
            )
            try {
                val (receipt, bytes) = decode(plain, row, fieldPath)
                try {
                    consume(receipt, bytes)
                } finally {
                    bytes.fill(0)
                }
            } finally {
                plain.fill(0)
            }
        }

    fun receipt(
        binding: QuarantinedWorkDraft,
        session: JavaRosaPrivateRepeatAnswerSession,
        rowId: String,
        fieldPath: String
    ): ProtectedRepeatImageReceipt? =
        withVerifiedImage(binding, session, rowId, fieldPath) { receipt, _ -> receipt }

    fun clear(
        binding: QuarantinedWorkDraft,
        session: JavaRosaPrivateRepeatAnswerSession,
        rowId: String,
        fieldPath: String
    ) = rows.withLockedCurrentInventory(binding, session.schema) { inventory ->
        val row = requireAllowed(inventory, session.schema, rowId, fieldPath)
        val file = fileFor(binding, row, fieldPath)
        if (file.exists()) {
            require(file.isFile &&
                !java.nio.file.Files.isSymbolicLink(file.toPath()) &&
                file.delete()) {
                "Cannot delete the authorized private repeat image"
            }
        }
    }

    private fun requireAllowed(
        inventory: ProtectedRepeatInventory,
        schema: PrivateRepeatSchema,
        rowId: String,
        fieldPath: String
    ): ProtectedRepeatRow {
        require(UUID.fromString(rowId).toString() == rowId)
        val row = inventory.rows.singleOrNull { it.rowId == rowId }
            ?: throw IllegalArgumentException("Unknown repeat row UUID")
        require(!row.isArchived &&
            inventory.active(row.groupPath).any { it.rowId == rowId }) {
            "Archived repeat image owner cannot be opened or modified"
        }
        val group = schema.groups.singleOrNull { it.path == row.groupPath }
            ?: throw IllegalArgumentException("Unknown native repeat group")
        require(group.imagePaths.isNotEmpty() &&
            group.imagePaths.size <= 2 &&
            group.imagePaths.toSet().size == group.imagePaths.size &&
            fieldPath in group.imagePaths &&
            fieldPath.startsWith(row.groupPath + "/") &&
            FIELD.matches(fieldPath)) {
            "Repeat image question is not native-reviewed for this UUID"
        }
        return row
    }

    private fun fileFor(
        binding: QuarantinedWorkDraft,
        row: ProtectedRepeatRow,
        fieldPath: String
    ): File {
        val dir = File(
            File(File(root, QuarantinedWorkDraftVault.VAULT_DIRECTORY),
                binding.projectId), binding.workId
        )
        require(dir.isDirectory &&
            !java.nio.file.Files.isSymbolicLink(dir.toPath()) &&
            dir.canonicalFile.parentFile?.name == binding.projectId &&
            dir.canonicalFile.parentFile?.parentFile?.name ==
                QuarantinedWorkDraftVault.VAULT_DIRECTORY) {
            "Private repeat image cannot escape its work-item vault"
        }
        val pathHash = QuarantinedPrivateImageStore.digest(
            fieldPath.toByteArray(Charsets.UTF_8)
        ).take(32)
        val file = File(
            dir, "repeat-image-" + row.rowId + "-" + pathHash + ".aesgcm"
        )
        require(!java.nio.file.Files.isSymbolicLink(file.toPath())) {
            "Symlinked repeat image is forbidden"
        }
        return file
    }

    private fun boundedRead(source: InputStream): ByteArray {
        ByteArrayOutputStream().use { result ->
            source.use { input ->
                val chunk = ByteArray(8192)
                while (true) {
                    val n = input.read(chunk)
                    if (n < 0) break
                    if (n == 0) continue
                    require(result.size() + n <= MAX_IMAGE_BYTES) {
                        "Repeat image exceeds protected 1.5 MB cap"
                    }
                    result.write(chunk, 0, n)
                }
            }
            require(result.size() >= 12) { "Empty repeat image is not allowed" }
            return result.toByteArray()
        }
    }

    private fun encode(
        receipt: ProtectedRepeatImageReceipt,
        row: ProtectedRepeatRow,
        image: ByteArray
    ): ByteArray {
        val result = ByteArrayOutputStream()
        DataOutputStream(result).use { out ->
            out.writeInt(FORMAT)
            out.writeUTF(row.rowId)
            out.writeUTF(row.groupPath)
            out.writeInt(row.creationSequence)
            out.writeUTF(fieldName(receipt.fieldPath))
            out.writeUTF(receipt.mime)
            out.writeUTF(receipt.sha256)
            out.writeInt(image.size)
            out.write(image)
        }
        return result.toByteArray()
    }

    private fun decode(
        plain: ByteArray,
        row: ProtectedRepeatRow,
        path: String
    ): Pair<ProtectedRepeatImageReceipt, ByteArray> {
        require(plain.size in 100..MAX_ENCRYPTED)
        val input = DataInputStream(ByteArrayInputStream(plain))
        require(input.readInt() == FORMAT &&
            input.readUTF() == row.rowId &&
            input.readUTF() == row.groupPath &&
            input.readInt() == row.creationSequence &&
            input.readUTF() == fieldName(path)) {
            "Protected repeat image belongs to a different row or question"
        }
        val mime = input.readUTF()
        val hash = input.readUTF()
        val size = input.readInt()
        require(mime in SUPPORTED && HASH.matches(hash) &&
            size in 12..MAX_IMAGE_BYTES && input.available() == size) {
            "Protected repeat image has invalid metadata"
        }
        val bytes = ByteArray(size)
        input.readFully(bytes)
        require(QuarantinedPrivateImageStore.sniffMime(bytes) == mime &&
            QuarantinedPrivateImageStore.digest(bytes) == hash &&
            input.available() == 0) {
            "Protected repeat image failed independent byte integrity check"
        }
        return ProtectedRepeatImageReceipt(row.rowId, path, mime, size, hash) to bytes
    }

    private fun fieldName(path: String): String {
        require(FIELD.matches(path))
        return path.substringAfterLast("/")
    }

    private fun associatedData(
        b: QuarantinedWorkDraft,
        schema: PrivateRepeatSchema,
        row: ProtectedRepeatRow,
        path: String
    ): ByteArray = listOf(
        "DC_PRIVATE_REPEAT_IMAGE_V1",
        b.projectId, b.actorContextSha256, b.workId,
        b.sourceSubmissionId, b.sourceInstanceId, b.targetFormVersionId,
        b.targetFormId, b.targetVersion, b.workType.name, b.visitKey.orEmpty(),
        b.reservedInstanceId, b.sourceXmlSha256,
        b.sourceXFormSha256.orEmpty(), b.targetXFormSha256,
        b.reviewedPaths.toString(), b.validationTransfers.toString(),
        b.requiresDeprecatedId.toString(),
        row.rowId, row.groupPath, row.creationSequence.toString(),
        row.creationRequestId, path,
        schema.groups.joinToString("|") { group ->
            group.path + ":" + group.fields.joinToString(",") {
                it.path + "=" + it.type.name
            } + ":" + group.imagePaths.joinToString(",")
        }
    ).joinToString("\n").toByteArray(Charsets.UTF_8)

    companion object {
        const val MAX_IMAGE_BYTES = QuarantinedPrivateImageStore.MAX_IMAGE_BYTES
        private const val FORMAT = 1
        private const val MAX_ENCRYPTED = 1_600_000
        private val FIELD = Regex(
            "/[A-Za-z_][A-Za-z0-9_.-]*/[A-Za-z_][A-Za-z0-9_.-]*/[A-Za-z_][A-Za-z0-9_.-]*"
        )
        private val HASH = Regex("[0-9a-f]{64}")
        private val SUPPORTED = setOf("image/jpeg", "image/png", "image/webp")
    }
}
