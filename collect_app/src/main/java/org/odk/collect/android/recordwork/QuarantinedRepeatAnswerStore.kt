package org.odk.collect.android.recordwork

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/**
 * AES-GCM encrypted values for ONE durable UUID row per file. The current
 * JavaRosa index is NEVER stored: row positions can shift after archiving.
 *
 * The caller must perform online READY/source/form/actor authorization before
 * every open/save. This store additionally checks the bound local identity,
 * current active row inventory, and uses the exact native repeat session to
 * verify ALL active answers on every save and read.
 */
data class ProtectedRepeatAnswerDraft(
    val rowId: String,
    val values: Map<String, String>,
    val hasSavedAnswers: Boolean,
    val canFinalize: Boolean = false,
    val canUpload: Boolean = false
) {
    init {
        require(!canFinalize && !canUpload) {
            "Repeat answer draft is not a submitted ODK instance"
        }
    }
}

class QuarantinedRepeatAnswerStore(
    private val noBackupRoot: File,
    private val cipher: PrivateDraftCipher,
    private val rowVault: QuarantinedRepeatRowVault =
        QuarantinedRepeatRowVault(noBackupRoot, cipher)
) {
    fun save(
        binding: QuarantinedWorkDraft,
        session: JavaRosaPrivateRepeatAnswerSession,
        rowId: String,
        entered: Map<String, String>
    ): ProtectedRepeatAnswerDraft =
        rowVault.withLockedCurrentInventory(binding, session.schema) { inventory ->
            val row = activeRow(inventory, rowId)
            val group = groupFor(session.schema, row)
            require(entered.keys.all { key -> group.fields.any { it.path == key } } &&
                entered.size <= group.fields.size) {
                "Repeat draft has answers outside the authorized row group"
            }
            // Rebuild the ENTIRE target JavaRosa repeat instance tree in
            // current active order; refuse any corrupt existing row data.
            val existing = allActiveValues(binding, session.schema, inventory)
            val candidates = existing.toMutableMap().apply { put(rowId, entered) }
            val validated = session.validate(inventory, candidates)
            val output = requireNotNull(validated[rowId]) {
                "Native repeat row was not applied"
            }
            val file = pathFor(binding, row)
            val plain = pack(row, group, output)
            try {
                val encrypted = cipher.seal(plain, aad(binding, session.schema, row))
                require(encrypted.size in 30..MAX_ENCRYPTED)
                val temp = File(file.parentFile, ".repeat-answer-" + UUID.randomUUID())
                try {
                    FileOutputStream(temp).use {
                        it.write(encrypted)
                        it.fd.sync()
                    }
                    // Do not delete a prior save if replacement fails.
                    require(temp.renameTo(file)) {
                        "Unable to atomically save protected repeated answers"
                    }
                } finally {
                    if (temp.exists()) temp.delete()
                }
            } finally {
                plain.fill(0)
            }
            ProtectedRepeatAnswerDraft(rowId, output, hasSavedAnswers = true)
        }

    fun read(
        binding: QuarantinedWorkDraft,
        session: JavaRosaPrivateRepeatAnswerSession,
        rowId: String
    ): ProtectedRepeatAnswerDraft =
        rowVault.withLockedCurrentInventory(binding, session.schema) { inventory ->
            val row = activeRow(inventory, rowId)
            val all = allActiveValues(binding, session.schema, inventory)
            // Validate recovered bytes against a fresh native target FormDef
            // BEFORE exposing any saved values to the editor.
            val validated = session.validate(inventory, all)
            val stored = all.containsKey(rowId)
            ProtectedRepeatAnswerDraft(
                row.rowId, if (stored) requireNotNull(validated[rowId])
                else emptyMap(), hasSavedAnswers = stored
            )
        }

    /**
     * Archived row data cannot be reopened or mapped onto a new row. Its
     * ciphertext remains inaccessible under the permanent archived UUID
     * tombstone until a future explicit, audited deletion policy exists.
     */
    private fun activeRow(
        inventory: ProtectedRepeatInventory,
        rowId: String
    ): ProtectedRepeatRow {
        val row = inventory.rows.singleOrNull { it.rowId == rowId }
            ?: throw IllegalArgumentException("Unknown repeat UUID")
        require(!row.isArchived &&
            inventory.active(row.groupPath).any { it.rowId == rowId }) {
            "Archived repeat rows cannot be read or edited"
        }
        return row
    }

    private fun groupFor(
        schema: PrivateRepeatSchema,
        row: ProtectedRepeatRow
    ): PrivateRepeatGroup = requireNotNull(
        schema.groups.singleOrNull { it.path == row.groupPath }
    ) { "Row belongs to an unsupported target repeat group" }

    private fun allActiveValues(
        binding: QuarantinedWorkDraft,
        schema: PrivateRepeatSchema,
        inventory: ProtectedRepeatInventory
    ): Map<String, Map<String, String>> {
        val result = linkedMapOf<String, Map<String, String>>()
        for (group in schema.groups) {
            for (row in inventory.active(group.path)) {
                val file = pathFor(binding, row)
                if (!file.exists()) continue
                require(file.isFile &&
                    !java.nio.file.Files.isSymbolicLink(file.toPath()) &&
                    file.length() in 30..MAX_ENCRYPTED.toLong()) {
                    "Invalid encrypted repeated answer file"
                }
                val clear = cipher.open(
                    file.readBytes(), aad(binding, schema, row)
                )
                try {
                    result[row.rowId] = unpack(clear, row, group)
                } finally {
                    clear.fill(0)
                }
            }
        }
        return result
    }

    private fun pack(
        row: ProtectedRepeatRow,
        group: PrivateRepeatGroup,
        values: Map<String, String>
    ): ByteArray {
        require(values.keys == group.fields.map { it.path }.toSet() &&
            values.values.all { it.length <= JavaRosaPrivateRepeatAnswerSession.MAX_TEXT })
        val output = ByteArrayOutputStream()
        DataOutputStream(output).use { writer ->
            writer.writeInt(FORMAT)
            writer.writeUTF(row.rowId)
            writer.writeUTF(row.groupPath)
            writer.writeInt(row.creationSequence)
            writer.writeInt(group.fields.size)
            for (field in group.fields) {
                writer.writeUTF(field.path)
                val bytes = requireNotNull(values[field.path])
                    .toByteArray(Charsets.UTF_8)
                require(bytes.size <= MAX_ANSWER_BYTES)
                writer.writeInt(bytes.size)
                writer.write(bytes)
            }
        }
        require(output.size() in 30..MAX_PLAINTEXT)
        return output.toByteArray()
    }

    private fun unpack(
        clear: ByteArray,
        row: ProtectedRepeatRow,
        group: PrivateRepeatGroup
    ): Map<String, String> {
        require(clear.size in 30..MAX_PLAINTEXT)
        val reader = DataInputStream(ByteArrayInputStream(clear))
        require(reader.readInt() == FORMAT &&
            reader.readUTF() == row.rowId &&
            reader.readUTF() == row.groupPath &&
            reader.readInt() == row.creationSequence &&
            reader.readInt() == group.fields.size) {
            "Saved row identity does not match active JavaRosa group"
        }
        val values = linkedMapOf<String, String>()
        for (field in group.fields) {
            require(reader.readUTF() == field.path) {
                "Saved answer order or schema changed"
            }
            val length = reader.readInt()
            require(length in 0..MAX_ANSWER_BYTES &&
                length <= reader.available()) {
                "Malformed private repeat answer length"
            }
            val bytes = ByteArray(length)
            reader.readFully(bytes)
            val answer = String(bytes, Charsets.UTF_8)
            require(answer.length <= JavaRosaPrivateRepeatAnswerSession.MAX_TEXT)
            values[field.path] = answer
            bytes.fill(0)
        }
        require(reader.available() == 0)
        return values
    }

    private fun pathFor(
        binding: QuarantinedWorkDraft,
        row: ProtectedRepeatRow
    ): File {
        require(UUID.fromString(row.rowId).toString() == row.rowId)
        val dir = File(
            File(
                File(noBackupRoot, QuarantinedWorkDraftVault.VAULT_DIRECTORY),
                binding.projectId
            ), binding.workId
        )
        require(dir.isDirectory &&
            !java.nio.file.Files.isSymbolicLink(dir.toPath()) &&
            dir.canonicalFile.parentFile?.name == binding.projectId &&
            dir.canonicalFile.parentFile?.parentFile?.name ==
                QuarantinedWorkDraftVault.VAULT_DIRECTORY) {
            "Private repeat answer path escaped the bound work directory"
        }
        val file = File(dir, "repeat-answer-" + row.rowId + ".aesgcm")
        require(!java.nio.file.Files.isSymbolicLink(file.toPath())) {
            "Symlinked private repeat answer refused"
        }
        return file
    }

    private fun aad(
        b: QuarantinedWorkDraft,
        schema: PrivateRepeatSchema,
        row: ProtectedRepeatRow
    ): ByteArray = listOf(
        "DC_REPEAT_ANSWER_V1",
        b.projectId, b.actorContextSha256, b.workId,
        b.sourceSubmissionId, b.sourceInstanceId,
        b.targetFormVersionId, b.targetFormId, b.targetVersion,
        b.workType.name, b.visitKey.orEmpty(),
        b.reservedInstanceId, b.sourceXmlSha256,
        b.sourceXFormSha256.orEmpty(), b.targetXFormSha256,
        b.reviewedPaths.toString(), b.validationTransfers.toString(),
        b.requiresDeprecatedId.toString(),
        row.rowId, row.groupPath, row.creationSequence.toString(),
        row.creationRequestId,
        schema.groups.joinToString("|") { group ->
            group.path + ":" + group.fields.joinToString(",") {
                it.path + "=" + it.type.name
            }
        }
    ).joinToString("\n").toByteArray(Charsets.UTF_8)

    companion object {
        private const val FORMAT = 1
        private const val MAX_ANSWER_BYTES = 65_536
        private const val MAX_PLAINTEXT = 300_000
        private const val MAX_ENCRYPTED = 305_000
    }
}
