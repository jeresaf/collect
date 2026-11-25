package org.odk.collect.android.recordwork

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.UUID

/**
 * An encrypted, tombstone-preserving, work-item-scoped ledger of stable
 * local repeat ROW IDs. The current JavaRosa ordinal is derived from the
 * active order each time. It is NEVER the durable identity of a row.
 *
 * This ledger contains NO repeat answers, media file paths or ODK instance
 * XML. Actual repeat widget edit/save/submit requires a separate gate.
 */
data class ProtectedRepeatRow(
    val rowId: String,
    val groupPath: String,
    val creationSequence: Int,
    val creationRequestId: String,
    val isArchived: Boolean = false,
    val archiveRequestId: String? = null
)

data class ProtectedRepeatInventory(
    val rows: List<ProtectedRepeatRow>,
    val canSaveAnswers: Boolean = false,
    val canFinalize: Boolean = false,
    val canUpload: Boolean = false
) {
    init {
        require(!canSaveAnswers && !canFinalize && !canUpload) {
            "Repeat identity ledger must never authorize an ODK Instance"
        }
    }

    fun active(group: String): List<ProtectedRepeatRow> = rows
        .filter { it.groupPath == group && !it.isArchived }
        .sortedBy { it.creationSequence }
}

class QuarantinedRepeatRowVault(
    private val noBackupRoot: File,
    private val cipher: PrivateDraftCipher,
    private val bindingVault: QuarantinedWorkDraftVault =
        QuarantinedWorkDraftVault(noBackupRoot)
) {
    /**
     * Idempotency key comes from the explicit user action. Replaying the
     * same request can never silently allocate another repeat row.
     */
    fun addRow(
        binding: QuarantinedWorkDraft,
        schema: PrivateRepeatSchema,
        groupPath: String,
        requestId: String
    ): ProtectedRepeatRow = synchronized(GLOBAL_LOCK) {
        verify(binding, schema)
        require(groupPath in schema.groups.map { it.path }) {
            "Repeat group was not proven by JavaRosa"
        }
        validateUuid(requestId)
        val existing = readInternal(binding, schema)
        existing.rows.singleOrNull { it.creationRequestId == requestId }?.let {
            require(it.groupPath == groupPath) {
                "Duplicate request ID used for a different repeat"
            }
            return@synchronized it
        }
        require(existing.rows.none { it.archiveRequestId == requestId }) {
            "Row creation request conflicts with an earlier archive"
        }
        require(existing.active(groupPath).size < MAX_ACTIVE_PER_GROUP &&
            existing.rows.size < MAX_STORED_ROWS) {
            "Private repeat row limit reached"
        }
        val sequence = (existing.rows.maxOfOrNull { it.creationSequence } ?: -1) + 1
        val row = ProtectedRepeatRow(
            rowId = UUID.randomUUID().toString(),
            groupPath = groupPath,
            creationSequence = sequence,
            creationRequestId = requestId
        )
        commit(binding, schema, existing.rows + row)
        row
    }

    /**
     * Tombstones are permanent for the lifetime of the work item: deletion
     * cannot make a new row reuse an old identity or corrupt the relationship
     * between later per-row answers and media references.
     */
    fun archiveRow(
        binding: QuarantinedWorkDraft,
        schema: PrivateRepeatSchema,
        rowId: String,
        requestId: String
    ): ProtectedRepeatRow = synchronized(GLOBAL_LOCK) {
        verify(binding, schema)
        validateUuid(rowId)
        validateUuid(requestId)
        val existing = readInternal(binding, schema)
        val target = existing.rows.singleOrNull { it.rowId == rowId }
            ?: throw IllegalArgumentException("Repeat row ID is not in this work item")
        if (target.isArchived) {
            require(target.archiveRequestId == requestId) {
                "Archived row cannot be archived with another operation"
            }
            return@synchronized target
        }
        require(existing.rows.none {
            it.archiveRequestId == requestId || it.creationRequestId == requestId
        }) {
            "Archive idempotency key belongs to a different row or creation"
        }
        val archived = target.copy(
            isArchived = true, archiveRequestId = requestId
        )
        commit(binding, schema, existing.rows.map {
            if (it.rowId == rowId) archived else it
        })
        archived
    }

    fun read(
        binding: QuarantinedWorkDraft,
        schema: PrivateRepeatSchema
    ): ProtectedRepeatInventory = synchronized(GLOBAL_LOCK) {
        verify(binding, schema)
        readInternal(binding, schema)
    }

    /**
     * Serializes each per-row answer save with identity create/archive within
     * this app process. The callback must not make network requests: the
     * caller must authorize the work before entering this guarded section.
     */
    fun <T> withLockedCurrentInventory(
        binding: QuarantinedWorkDraft,
        schema: PrivateRepeatSchema,
        block: (ProtectedRepeatInventory) -> T
    ): T = synchronized(GLOBAL_LOCK) {
        verify(binding, schema)
        block(readInternal(binding, schema))
    }

    private fun verify(
        binding: QuarantinedWorkDraft,
        schema: PrivateRepeatSchema
    ) {
        val active = requireNotNull(bindingVault.read(
            binding.projectId, binding.workId, binding.actorContextSha256
        )) { "Private work identity is not staged" }
        require(active == binding &&
            binding.state == QuarantinedWorkDraft.STATE_QUARANTINED &&
            !binding.canFinalize && !binding.canUpload && !binding.canOpenEditor &&
            schema.workId == binding.workId &&
            schema.targetXFormSha256 == binding.targetXFormSha256 &&
            schema.groups.isNotEmpty() &&
            schema.groups.size <= PrivateRepeatSchema.MAX_GROUPS &&
            schema.groups.map { it.path }.toSet().size == schema.groups.size &&
            schema.groups.all { group ->
                GROUP_PATH.matches(group.path) &&
                    group.fields.isNotEmpty() &&
                    group.fields.size <= PrivateRepeatSchema.MAX_FIELDS_PER_GROUP &&
                    group.fields.map { it.path }.toSet().size == group.fields.size &&
                    group.fields.all { field ->
                        field.path.startsWith(group.path + "/") &&
                            field.path.removePrefix(group.path + "/")
                                .matches(LEAF_NAME) &&
                            field.type in setOf(
                                PrivateScalarType.TEXT, PrivateScalarType.INTEGER,
                                PrivateScalarType.DECIMAL, PrivateScalarType.DATE
                            ) && field.choices.isEmpty()
                    } &&
                    group.imagePaths.size <= 2 &&
                    group.imagePaths.toSet().size == group.imagePaths.size &&
                    group.imagePaths.all { image ->
                        image.startsWith(group.path + "/") &&
                            image.removePrefix(group.path + "/").matches(LEAF_NAME) &&
                            group.fields.none { it.path == image }
                    }
            }) {
            "Repeat inventory binding/schema is unrecognized or changed"
        }
    }

    private fun readInternal(
        binding: QuarantinedWorkDraft,
        schema: PrivateRepeatSchema
    ): ProtectedRepeatInventory {
        val file = fileFor(binding)
        if (!file.exists()) return ProtectedRepeatInventory(emptyList())
        require(file.isFile && !java.nio.file.Files.isSymbolicLink(file.toPath()) &&
            file.length() in 30..MAX_ENVELOPE.toLong()) {
            "Invalid encrypted repeat inventory"
        }
        val plaintext = cipher.open(file.readBytes(), aad(binding, schema))
        try {
            require(plaintext.size in 8..MAX_PLAINTEXT)
            val input = DataInputStream(ByteArrayInputStream(plaintext))
            require(input.readInt() == FORMAT)
            val count = input.readInt()
            require(count in 0..MAX_STORED_ROWS)
            val rows = (0 until count).map {
                val row = ProtectedRepeatRow(
                    rowId = input.readUTF(),
                    groupPath = input.readUTF(),
                    creationSequence = input.readInt(),
                    creationRequestId = input.readUTF(),
                    isArchived = input.readBoolean(),
                    archiveRequestId = input.readUTF().ifEmpty { null }
                )
                validateRow(row, schema)
                row
            }
            require(input.available() == 0 &&
                rows.map { it.rowId }.toSet().size == rows.size &&
                rows.map { it.creationRequestId }.toSet().size == rows.size &&
                rows.map { it.creationSequence }.toSet().size == rows.size &&
                rows.map { it.creationSequence } == rows.map { it.creationSequence }.sorted() &&
                rows.flatMap { row -> listOf(row.creationRequestId) +
                    listOfNotNull(row.archiveRequestId)
                }.toSet().size == rows.sumOf { if (it.isArchived) 2 else 1 } &&
                schema.groups.all { ProtectedRepeatInventory(rows).active(it.path).size <=
                    MAX_ACTIVE_PER_GROUP }) {
                "Ambiguous, duplicate or reordered protected repeat identity"
            }
            return ProtectedRepeatInventory(rows)
        } finally {
            plaintext.fill(0)
        }
    }

    private fun commit(
        binding: QuarantinedWorkDraft,
        schema: PrivateRepeatSchema,
        rows: List<ProtectedRepeatRow>
    ) {
        require(rows.size <= MAX_STORED_ROWS)
        val file = fileFor(binding)
        val output = ByteArrayOutputStream()
        DataOutputStream(output).use { out ->
            out.writeInt(FORMAT)
            out.writeInt(rows.size)
            rows.forEach { row ->
                validateRow(row, schema)
                out.writeUTF(row.rowId)
                out.writeUTF(row.groupPath)
                out.writeInt(row.creationSequence)
                out.writeUTF(row.creationRequestId)
                out.writeBoolean(row.isArchived)
                out.writeUTF(row.archiveRequestId.orEmpty())
            }
        }
        val clear = output.toByteArray()
        try {
            require(clear.size <= MAX_PLAINTEXT)
            val encrypted = cipher.seal(clear, aad(binding, schema))
            require(encrypted.size in 30..MAX_ENVELOPE)
            val pending = File(
                file.parentFile, ".repeat-ledger-" + UUID.randomUUID()
            )
            try {
                FileOutputStream(pending).use { stream ->
                    stream.write(encrypted)
                    stream.fd.sync()
                }
                // No delete-first fallback: failed atomic replacement keeps
                // the previous encrypted ledger and its stable row identities.
                require(pending.renameTo(file)) {
                    "Unable to atomically persist the protected row identities"
                }
            } finally {
                if (pending.exists()) pending.delete()
            }
        } finally {
            clear.fill(0)
        }
    }

    private fun fileFor(binding: QuarantinedWorkDraft): File {
        val folder = File(
            File(
                File(noBackupRoot, QuarantinedWorkDraftVault.VAULT_DIRECTORY),
                binding.projectId
            ), binding.workId
        )
        require(folder.isDirectory &&
            !java.nio.file.Files.isSymbolicLink(folder.toPath()) &&
            folder.canonicalFile.parentFile?.name == binding.projectId &&
            folder.canonicalFile.parentFile?.parentFile?.name ==
                QuarantinedWorkDraftVault.VAULT_DIRECTORY) {
            "Protected repeat rows may not escape the private work vault"
        }
        return File(folder, "repeat-row-identities.aesgcm")
    }

    private fun aad(
        binding: QuarantinedWorkDraft,
        schema: PrivateRepeatSchema
    ): ByteArray = listOf(
        "DC_REPEAT_ROW_IDENTITY_V1",
        binding.projectId, binding.actorContextSha256,
        binding.workId, binding.sourceSubmissionId, binding.sourceInstanceId,
        binding.targetFormVersionId, binding.targetFormId,
        binding.targetVersion, binding.workType.name,
        binding.visitKey.orEmpty(), binding.reservedInstanceId,
        binding.sourceXmlSha256, binding.sourceXFormSha256.orEmpty(),
        binding.targetXFormSha256, binding.reviewedPaths.toString(),
        binding.validationTransfers.toString(),
        binding.requiresDeprecatedId.toString(),
        schema.groups.joinToString("|") { group ->
            group.path + "[" + group.fields.joinToString(",") {
                it.path + ":" + it.type.name
            } + "]" + if (group.imagePaths.isEmpty()) "" else
                "{native-images:" + group.imagePaths.joinToString(",") + "}"
        }
    ).joinToString("\n").toByteArray(Charsets.UTF_8)

    private fun validateRow(
        row: ProtectedRepeatRow,
        schema: PrivateRepeatSchema
    ) {
        validateUuid(row.rowId)
        validateUuid(row.creationRequestId)
        row.archiveRequestId?.let(::validateUuid)
        require(row.creationSequence in 0..MAX_STORED_ROWS &&
            row.groupPath in schema.groups.map { it.path } &&
            row.isArchived == (row.archiveRequestId != null)) {
            "Invalid repeat row state or path"
        }
    }

    companion object {
        private val GLOBAL_LOCK = ProtectedDraftComponentLock.monitor
        private const val FORMAT = 1
        const val MAX_ACTIVE_PER_GROUP = 20
        private const val MAX_STORED_ROWS = 120
        private const val MAX_PLAINTEXT = 131_072
        private const val MAX_ENVELOPE = 132_000
        private val GROUP_PATH =
            Regex("/[A-Za-z_][A-Za-z0-9_.-]*/[A-Za-z_][A-Za-z0-9_.-]*")
        private val LEAF_NAME = Regex("[A-Za-z_][A-Za-z0-9_.-]*")

        private fun validateUuid(value: String) {
            require(value.length == 36 &&
                UUID.fromString(value).toString() == value) {
                "Repeat row identifier must be a canonical UUID"
            }
        }
    }
}
