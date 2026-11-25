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
 * Durable evidence of explicit enumerator choices made for EVERY target field.
 * This is a local, encrypted approval of an individual mapping review, NOT
 * server authorization, not a source answer cache, and not a submit token.
 *
 * Source XML / XForms / plaintext answers are NEVER stored in this manifest.
 * The caller must obtain fresh READY server permission for every use.
 */
data class ProtectedMappingApproval(
    val approvalId: String,
    val approvedAtEpochMillis: Long,
    val workId: String,
    val sourceXmlSha256: String,
    val targetXFormSha256: String,
    val decisions: Map<String, MappingDecision>,
    val canFinalize: Boolean = false,
    val canUpload: Boolean = false
) {
    init {
        require(!canFinalize && !canUpload) {
            "A local mapping decision cannot authorize finalization or upload"
        }
    }

    val approvedTransferPaths: Set<String>
        get() = decisions.filterValues {
            it == MappingDecision.REQUEST_REVIEWED_TRANSFER
        }.keys
}

class ProtectedMappingApprovalStore(
    private val noBackupRoot: File,
    private val cipher: PrivateDraftCipher,
    private val vault: QuarantinedWorkDraftVault =
        QuarantinedWorkDraftVault(noBackupRoot)
) {
    fun save(
        binding: QuarantinedWorkDraft,
        plan: HistoricalMappingPlan,
        review: MappingReviewSnapshot
    ): ProtectedMappingApproval = synchronized(ProtectedDraftComponentLock.monitor) {
        ProtectedSubmissionAttemptGuard.requireEditable(noBackupRoot, binding)
        verifyBinding(binding, plan)
        require(review.workId == binding.workId &&
            review.sourceXFormSha256 == binding.sourceXFormSha256 &&
            review.targetXFormSha256 == binding.targetXFormSha256 &&
            review.reviewedEveryTargetPath && !review.canSubmit &&
            review.decisions.keys == plan.candidates.map { it.targetPath }.toSet()) {
            "All exact target paths need an explicit operator decision"
        }
        validateDecisions(binding, plan, review.decisions)
        val file = manifestFile(binding)
        if (file.exists()) {
            val prior = requireNotNull(read(binding, plan))
            require(prior.decisions == review.decisions) {
                "Existing mapping approval cannot be silently changed or overwritten"
            }
            return@synchronized prior
        }

        val approval = ProtectedMappingApproval(
            approvalId = UUID.randomUUID().toString(),
            approvedAtEpochMillis = System.currentTimeMillis(),
            workId = binding.workId,
            sourceXmlSha256 = binding.sourceXmlSha256,
            targetXFormSha256 = binding.targetXFormSha256,
            decisions = review.decisions.toMap()
        )
        val bytes = encode(binding, plan, approval)
        try {
            val protectedBytes = cipher.seal(bytes, aad(binding, plan))
            require(protectedBytes.size in 30..MAX_CIPHERTEXT) {
                "Mapping approval ciphertext exceeds bounds"
            }
            val pending = File(
                file.parentFile, ".mapping-approval-" + UUID.randomUUID()
            )
            try {
                FileOutputStream(pending).use { output ->
                    output.write(protectedBytes)
                    output.fd.sync()
                }
                // Refuse a preexisting manifest even across independent store
                // objects. Never use a delete-first replacement.
                require(!file.exists() && pending.renameTo(file)) {
                    "A different approval already exists or atomic save failed"
                }
            } finally {
                if (pending.exists()) pending.delete()
            }
        } finally {
            bytes.fill(0)
        }
        return@synchronized requireNotNull(read(binding, plan)) {
            "Encrypted mapping approval did not survive authenticated readback"
        }
    }

    @Synchronized
    fun read(
        binding: QuarantinedWorkDraft,
        plan: HistoricalMappingPlan
    ): ProtectedMappingApproval? {
        verifyBinding(binding, plan)
        val file = manifestFile(binding)
        if (!file.exists()) return null
        require(file.isFile && !java.nio.file.Files.isSymbolicLink(file.toPath()) &&
            file.length() in 30..MAX_CIPHERTEXT.toLong()) {
            "Encrypted mapping approval is damaged"
        }
        val clear = cipher.open(file.readBytes(), aad(binding, plan))
        try {
            require(clear.size in 24..MAX_PLAINTEXT) {
                "Plaintext mapping evidence exceeds bounds"
            }
            val input = DataInputStream(ByteArrayInputStream(clear))
            require(input.readInt() == FORMAT)
            val approvalId = input.readUTF()
            val timestamp = input.readLong()
            val workId = input.readUTF()
            val sourceHash = input.readUTF()
            val targetHash = input.readUTF()
            val count = input.readInt()
            require(count in 1..MAX_PATHS && count == plan.candidates.size &&
                approvalId == UUID.fromString(approvalId).toString() &&
                timestamp > 1_577_836_800_000L &&
                timestamp <= System.currentTimeMillis() + 86_400_000L &&
                workId == binding.workId &&
                sourceHash == binding.sourceXmlSha256 &&
                targetHash == binding.targetXFormSha256) {
                "Mapping approval version/work identity mismatch"
            }
            val choices = linkedMapOf<String, MappingDecision>()
            for (candidate in plan.candidates) {
                val path = input.readUTF()
                require(path == candidate.targetPath && path !in choices) {
                    "Approval path inventory changed"
                }
                val action = MappingDecision.valueOf(input.readUTF())
                choices[path] = action
            }
            require(input.available() == 0) {
                "Unexpected trailing approval data"
            }
            validateDecisions(binding, plan, choices)
            return ProtectedMappingApproval(
                approvalId, timestamp, workId, sourceHash,
                targetHash, choices.toMap()
            )
        } finally {
            clear.fill(0)
        }
    }

    private fun encode(
        binding: QuarantinedWorkDraft,
        plan: HistoricalMappingPlan,
        approval: ProtectedMappingApproval
    ): ByteArray {
        require(approval.decisions.keys == plan.candidates.map {
            it.targetPath
        }.toSet())
        val result = ByteArrayOutputStream()
        DataOutputStream(result).use { out ->
            out.writeInt(FORMAT)
            out.writeUTF(approval.approvalId)
            out.writeLong(approval.approvedAtEpochMillis)
            out.writeUTF(binding.workId)
            out.writeUTF(binding.sourceXmlSha256)
            out.writeUTF(binding.targetXFormSha256)
            out.writeInt(plan.candidates.size)
            plan.candidates.forEach { candidate ->
                out.writeUTF(candidate.targetPath)
                out.writeUTF(requireNotNull(
                    approval.decisions[candidate.targetPath]
                ).name)
            }
        }
        require(result.size() in 24..MAX_PLAINTEXT)
        return result.toByteArray()
    }

    private fun verifyBinding(
        binding: QuarantinedWorkDraft,
        plan: HistoricalMappingPlan
    ) {
        val current = requireNotNull(vault.read(
            binding.projectId, binding.workId, binding.actorContextSha256
        )) { "Protected work binding not found" }
        require(current == binding &&
            binding.state == QuarantinedWorkDraft.STATE_QUARANTINED &&
            !binding.canOpenEditor && !binding.canFinalize && !binding.canUpload &&
            plan.workId == binding.workId &&
            plan.sourceSubmissionId == binding.sourceSubmissionId &&
            plan.targetFormVersionId == binding.targetFormVersionId &&
            plan.workType == binding.workType &&
            plan.sourceXFormSha256 == binding.sourceXFormSha256 &&
            plan.targetXFormSha256 == binding.targetXFormSha256 &&
            plan.targetFormId == binding.targetFormId &&
            plan.targetVersion == binding.targetVersion &&
            plan.candidates.size == binding.reviewedPaths &&
            plan.candidates.size in 1..MAX_PATHS &&
            plan.candidates.map { it.targetPath }.toSet().size ==
                plan.candidates.size) {
            "Mapping decisions do not match the currently bound work/schema"
        }
    }

    private fun validateDecisions(
        binding: QuarantinedWorkDraft,
        plan: HistoricalMappingPlan,
        decisions: Map<String, MappingDecision>
    ) {
        require(decisions.keys == plan.candidates.map {
            it.targetPath
        }.toSet())
        val transfers = plan.candidates.filter {
            decisions[it.targetPath] == MappingDecision.REQUEST_REVIEWED_TRANSFER
        }
        require(transfers.size == binding.validationTransfers &&
            (binding.workType != RecordWorkType.FOLLOW_UP || transfers.isEmpty()) &&
            transfers.all {
                it.safeScalarProposal &&
                    it.sourcePath == it.targetPath &&
                    it.risks.isEmpty()
            } &&
            (transfers.isEmpty() || binding.sourceXFormSha256 != null)) {
            "Unsafe, unproven or unreviewed answer transfer requested"
        }
    }

    private fun manifestFile(binding: QuarantinedWorkDraft): File {
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
            "Mapping approval escaped the private work vault"
        }
        val file = File(dir, "mapping-approval.aesgcm")
        require(!java.nio.file.Files.isSymbolicLink(file.toPath()))
        return file
    }

    private fun aad(
        b: QuarantinedWorkDraft,
        plan: HistoricalMappingPlan
    ): ByteArray = listOf(
        "DC_PROTECTED_MAPPING_APPROVAL_V1",
        b.projectId, b.actorContextSha256, b.workId,
        b.sourceSubmissionId, b.sourceInstanceId,
        b.targetFormVersionId, b.targetFormId, b.targetVersion,
        b.workType.name, b.visitKey.orEmpty(), b.reservedInstanceId,
        b.sourceXmlSha256, b.sourceXFormSha256.orEmpty(), b.targetXFormSha256,
        b.reviewedPaths.toString(), b.validationTransfers.toString(),
        b.requiresDeprecatedId.toString(),
        plan.candidates.joinToString("|") { candidate ->
            candidate.targetPath + ":" + candidate.sourcePath.orEmpty() +
                ":" + candidate.risks.map { it.name }.sorted().joinToString(",") +
                ":" + candidate.safeScalarProposal
        }
    ).joinToString("\n").toByteArray(Charsets.UTF_8)

    companion object {
        private const val FORMAT = 1
        private const val MAX_PATHS = 30
        private const val MAX_PLAINTEXT = 20_000
        private const val MAX_CIPHERTEXT = 22_000
        fun digest(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256")
                .digest(bytes)
                .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}
