package org.odk.collect.android.recordwork

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.UUID

/** An operator decision about a future mapping rule; never transfer authority. */
enum class TypedReviewDecision { REENTER, LEAVE_EMPTY, REQUEST_SEPARATE_RULE_REVIEW }

data class ProtectedTypedReview(
    val reviewId: String,
    val recordedAtEpochMillis: Long,
    val workId: String,
    val sourceXmlSha256: String,
    val sourceXFormSha256: String,
    val targetXFormSha256: String,
    val decisions: Map<String, TypedReviewDecision>,
    val findingDigest: String,
    val canTransfer: Boolean = false,
    val canFinalize: Boolean = false,
    val canUpload: Boolean = false
) {
    init {
        require(!canTransfer && !canFinalize && !canUpload) {
            "A local typed-field review is not transfer, finalize or upload permission"
        }
    }
}

/**
 * Separately encrypted, immutable 3B-2 operator review evidence.
 * Does not replace the 3A mapping-approval manifest, read source answers,
 * issue server-side authority or grant historical migration.
 *
 * Caller MUST freshly authorize READY work and produce native-verified
 * findings before save/read. Actual transfer remains explicitly disabled.
 */
class ProtectedTypedReviewStore(
    private val noBackupRoot: File,
    private val cipher: PrivateDraftCipher,
    private val vault: QuarantinedWorkDraftVault = QuarantinedWorkDraftVault(noBackupRoot)
) {
    fun save(
        binding: QuarantinedWorkDraft,
        plan: HistoricalMappingPlan,
        findings: List<TypedMappingFinding>,
        decisions: Map<String, TypedReviewDecision>
    ): ProtectedTypedReview = synchronized(ProtectedDraftComponentLock.monitor) {
        ProtectedSubmissionAttemptGuard.requireEditable(noBackupRoot, binding)
        val digest = validate(binding, plan, findings, decisions)
        val file = fileOf(binding)
        if (file.exists()) {
            val existing = requireNotNull(read(binding, plan, findings))
            require(existing.decisions == decisions && existing.findingDigest == digest) {
                "Typed review is immutable; changing decisions needs a new authorized work item"
            }
            return@synchronized existing
        }
        val approval = ProtectedTypedReview(
            UUID.randomUUID().toString(), System.currentTimeMillis(),
            binding.workId, binding.sourceXmlSha256,
            requireNotNull(binding.sourceXFormSha256),
            binding.targetXFormSha256, decisions.toMap(), digest
        )
        val data = ByteArrayOutputStream().also { output ->
            DataOutputStream(output).use { out ->
                out.writeInt(FORMAT)
                out.writeUTF(approval.reviewId)
                out.writeLong(approval.recordedAtEpochMillis)
                out.writeUTF(approval.workId)
                out.writeUTF(approval.sourceXmlSha256)
                out.writeUTF(approval.sourceXFormSha256)
                out.writeUTF(approval.targetXFormSha256)
                out.writeUTF(approval.findingDigest)
                out.writeInt(findings.size)
                findings.forEach { finding ->
                    out.writeUTF(finding.targetPath)
                    out.writeUTF(requireNotNull(decisions[finding.targetPath]).name)
                }
            }
        }.toByteArray()
        try {
            require(data.size in 24..MAX_CLEAR)
            val encrypted = cipher.seal(data, aad(binding, digest))
            require(encrypted.size in 30..MAX_CIPHER)
            val pending = File(file.parentFile, ".typed-review-" + UUID.randomUUID())
            try {
                FileOutputStream(pending).use {
                    it.write(encrypted)
                    it.fd.sync()
                }
                require(!file.exists() && pending.renameTo(file)) {
                    "Typed review already exists or encrypted commit failed"
                }
            } finally {
                if (pending.exists()) pending.delete()
            }
        } finally {
            data.fill(0)
        }
        return@synchronized requireNotNull(read(binding, plan, findings))
    }

    @Synchronized
    fun read(
        binding: QuarantinedWorkDraft,
        plan: HistoricalMappingPlan,
        findings: List<TypedMappingFinding>
    ): ProtectedTypedReview? {
        // Derive expected inventory before touching ciphertext.
        val expected = findings.associate { it.targetPath to TypedReviewDecision.REENTER }
        val digest = validate(binding, plan, findings, expected)
        val file = fileOf(binding)
        if (!file.exists()) return null
        require(file.isFile && !java.nio.file.Files.isSymbolicLink(file.toPath()) &&
            file.length() in 30..MAX_CIPHER.toLong()) {
            "Encrypted typed review was damaged"
        }
        val clear = cipher.open(file.readBytes(), aad(binding, digest))
        try {
            require(clear.size in 24..MAX_CLEAR)
            val input = DataInputStream(ByteArrayInputStream(clear))
            require(input.readInt() == FORMAT)
            val id = input.readUTF()
            val time = input.readLong()
            val work = input.readUTF()
            val sourceHash = input.readUTF()
            val oldHash = input.readUTF()
            val targetHash = input.readUTF()
            val storedDigest = input.readUTF()
            val count = input.readInt()
            require(id == UUID.fromString(id).toString() &&
                time > 1_577_836_800_000L &&
                time <= System.currentTimeMillis() + 86_400_000L &&
                work == binding.workId &&
                sourceHash == binding.sourceXmlSha256 &&
                oldHash == binding.sourceXFormSha256 &&
                targetHash == binding.targetXFormSha256 &&
                storedDigest == digest && count == findings.size) {
                "Typed review context or inventory changed"
            }
            val decisions = linkedMapOf<String, TypedReviewDecision>()
            findings.forEach { finding ->
                val path = input.readUTF()
                require(path == finding.targetPath && path !in decisions)
                decisions[path] = TypedReviewDecision.valueOf(input.readUTF())
            }
            require(input.available() == 0)
            validate(binding, plan, findings, decisions)
            return ProtectedTypedReview(id, time, work, sourceHash, oldHash,
                targetHash, decisions.toMap(), digest)
        } finally {
            clear.fill(0)
        }
    }

    private fun validate(
        b: QuarantinedWorkDraft,
        plan: HistoricalMappingPlan,
        findings: List<TypedMappingFinding>,
        decisions: Map<String, TypedReviewDecision>
    ): String {
        val current = requireNotNull(vault.read(b.projectId, b.workId, b.actorContextSha256)) {
            "Protected work binding no longer exists"
        }
        require(current == b && b.workType == RecordWorkType.AMENDMENT &&
            b.state == QuarantinedWorkDraft.STATE_QUARANTINED &&
            !b.canOpenEditor && !b.canFinalize && !b.canUpload &&
            b.sourceXFormSha256 != null &&
            plan.workId == b.workId &&
            plan.sourceSubmissionId == b.sourceSubmissionId &&
            plan.targetFormVersionId == b.targetFormVersionId &&
            plan.sourceXFormSha256 == b.sourceXFormSha256 &&
            plan.targetXFormSha256 == b.targetXFormSha256 &&
            plan.workType == b.workType &&
            plan.candidates.size == findings.size &&
            findings.isNotEmpty() && findings.size <= 30 &&
            findings.map { it.targetPath } ==
                plan.candidates.map { it.targetPath } &&
            decisions.keys == findings.map { it.targetPath }.toSet() &&
            findings.all { !it.canTransfer && !it.canFinalize && !it.canUpload }) {
            "Typed review is not bound to current native schema and work"
        }
        require(findings.all { f ->
            decisions[f.targetPath] != TypedReviewDecision.REQUEST_SEPARATE_RULE_REVIEW ||
                f.verdict == TypedMappingVerdict.REVIEW_SAME_TYPE
        }) { "A blocked field cannot request an automatic mapping rule" }
        val material = buildString {
            append("DC_TYPED_MAPPING_REVIEW_V1\n")
            append(b.projectId).append('\n').append(b.actorContextSha256).append('\n')
            append(b.workId).append('\n').append(b.sourceSubmissionId).append('\n')
            append(b.sourceInstanceId).append('\n').append(b.reservedInstanceId).append('\n')
            append(b.sourceXmlSha256).append('\n')
            append(b.sourceXFormSha256).append('\n').append(b.targetXFormSha256).append('\n')
            append(b.targetFormVersionId).append('\n').append(b.targetVersion).append('\n')
            findings.forEach {
                append(it.targetPath).append('|').append(it.nativeType.name)
                    .append('|').append(it.verdict.name).append('|').append(it.reason)
                    .append('\n')
            }
        }
        return MessageDigest.getInstance("SHA-256")
            .digest(material.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private fun fileOf(b: QuarantinedWorkDraft): File {
        val directory = File(File(File(noBackupRoot,
            QuarantinedWorkDraftVault.VAULT_DIRECTORY), b.projectId), b.workId)
        require(directory.isDirectory &&
            !java.nio.file.Files.isSymbolicLink(directory.toPath()) &&
            directory.canonicalFile.parentFile?.name == b.projectId &&
            directory.canonicalFile.parentFile?.parentFile?.name ==
                QuarantinedWorkDraftVault.VAULT_DIRECTORY) {
            "Typed review escaped protected no-backup vault"
        }
        return File(directory, "typed-mapping-review.aesgcm").also {
            require(!java.nio.file.Files.isSymbolicLink(it.toPath()))
        }
    }

    private fun aad(b: QuarantinedWorkDraft, digest: String): ByteArray =
        listOf("DC_TYPED_REVIEW_CIPHERTEXT_V1", b.projectId,
            b.actorContextSha256, b.workId, b.sourceSubmissionId,
            b.sourceInstanceId, b.reservedInstanceId, b.sourceXmlSha256,
            b.sourceXFormSha256.orEmpty(), b.targetXFormSha256,
            b.targetFormVersionId, b.targetVersion, b.workType.name,
            digest).joinToString("\n").toByteArray(Charsets.UTF_8)

    companion object {
        private const val FORMAT = 1
        private const val MAX_CLEAR = 20_000
        private const val MAX_CIPHER = 22_000
    }
}
