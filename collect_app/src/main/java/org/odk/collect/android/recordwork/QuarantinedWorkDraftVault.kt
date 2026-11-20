package org.odk.collect.android.recordwork

import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.Properties
import java.util.UUID

/**
 * Durable *inert* work-item binding, not a native ODK Instance or XForm.
 * The vault MUST be rooted under Context.noBackupFilesDir, never the public
 * ODK instances directory. It contains no XML, answers, credentials or media.
 */
data class QuarantinedWorkDraft(
    val projectId: String,
    val actorContextSha256: String,
    val workId: String,
    val sourceSubmissionId: String,
    val sourceInstanceId: String,
    val targetFormVersionId: String,
    val targetFormId: String,
    val targetVersion: String,
    val workType: RecordWorkType,
    val visitKey: String?,
    val reservedInstanceId: String,
    val sourceXmlSha256: String,
    val sourceXFormSha256: String?,
    val targetXFormSha256: String,
    val reviewedPaths: Int,
    val validationTransfers: Int,
    val requiresDeprecatedId: Boolean,
    val state: String = STATE_QUARANTINED,
    val canOpenEditor: Boolean = false,
    val canFinalize: Boolean = false,
    val canUpload: Boolean = false
) {
    init {
        require(state == STATE_QUARANTINED &&
            !canOpenEditor && !canFinalize && !canUpload) {
            "The quarantine is not an editor or an upload authorization"
        }
    }

    companion object {
        const val STATE_QUARANTINED = "QUARANTINED_AWAITING_EDITOR"
    }
}

class QuarantinedWorkDraftVault(private val privateNoBackupRoot: File) {
    /**
     * Call only after a fresh owner-authorized server preparation. AMENDMENT
     * additionally requires fully reviewed, native-validated transfer
     * evidence. FOLLOW_UP reserves a distinct observation without copying.
     *
     * Repeated staging of the same unchanged work item is idempotent and
     * returns its original reserved instance ID; changed work data is blocked.
     */
    @Synchronized
    fun stage(
        projectId: String,
        actorContextSha256: String,
        prepared: PreparedRecordPreview,
        review: MappingReviewSnapshot,
        transfer: ReviewedTransferEvidence? = null
    ): QuarantinedWorkDraft {
        require(PROJECT_KEY.matches(projectId) && DIGEST.matches(actorContextSha256)) {
            "Invalid local project or user context"
        }
        checkUuid(prepared.workId)
        checkUuid(prepared.sourceSubmissionId)
        checkUuid(prepared.targetFormVersionId)
        val sourceInstanceId = requireNotNull(prepared.sourceInstanceId) {
            "Server source instanceID required before staging"
        }
        require(sourceInstanceId.startsWith("uuid:"))
        checkUuid(sourceInstanceId.removePrefix("uuid:"))
        val plan = requireNotNull(prepared.mappingPlan)
        val assessment = requireNotNull(prepared.assessment)
        val native = requireNotNull(prepared.nativeStaging)
        require(prepared.mappingApprovalRequired &&
            native.targetJavaRosaInitialized &&
            native.workId == prepared.workId &&
            native.sourceSubmissionId == prepared.sourceSubmissionId &&
            native.targetFormVersionId == prepared.targetFormVersionId &&
            native.targetXFormSha256 == plan.targetXFormSha256 &&
            plan.workId == prepared.workId &&
            plan.sourceSubmissionId == prepared.sourceSubmissionId &&
            plan.targetFormVersionId == prepared.targetFormVersionId &&
            plan.targetFormId == prepared.targetFormId &&
            plan.targetVersion == prepared.targetFormVersion &&
            plan.workType == prepared.type &&
            assessment.sourceSubmissionId == prepared.sourceSubmissionId &&
            assessment.sourceSha256.matches(DIGEST) &&
            assessment.targetXFormSha256 == plan.targetXFormSha256) {
            "Native and server evidence must refer to the same exact work"
        }
        require(review.workId == prepared.workId &&
            review.sourceXFormSha256 == plan.sourceXFormSha256 &&
            review.targetXFormSha256 == plan.targetXFormSha256 &&
            review.reviewedEveryTargetPath &&
            review.decisions.keys == plan.candidates.map { it.targetPath }.toSet() &&
            review.decisions.size == plan.candidates.size) {
            "Complete unchanged operator review required"
        }
        val expectedTransfers = review.decisions.values.count {
            it == MappingDecision.REQUEST_REVIEWED_TRANSFER
        }
        val visitKey: String?
        if (prepared.type == RecordWorkType.AMENDMENT) {
            require(prepared.visitKey == null && native.historicalJavaRosaInitialized) {
                "An amendment needs the exact historical native form and no visit key"
            }
            val proven = requireNotNull(transfer) {
                "Native reviewed transfer evidence required"
            }
            require(proven.workId == prepared.workId &&
                proven.sourceSubmissionId == prepared.sourceSubmissionId &&
                proven.targetFormVersionId == prepared.targetFormVersionId &&
                proven.workType == RecordWorkType.AMENDMENT &&
                proven.sourceInstanceId == sourceInstanceId &&
                proven.sourceXmlSha256 == assessment.sourceSha256 &&
                proven.sourceXFormSha256 == plan.sourceXFormSha256 &&
                proven.targetXFormSha256 == plan.targetXFormSha256 &&
                proven.pathsReviewed == plan.candidates.size &&
                proven.pathsTransferred == expectedTransfers &&
                !proven.canSaveDraft && !proven.canSubmit) {
                "Native transfer proof is stale, mismatched or incomplete"
            }
            visitKey = null
        } else {
            require(transfer == null && expectedTransfers == 0) {
                "Independent follow-up must never copy source answers"
            }
            visitKey = requireNotNull(prepared.visitKey) {
                "Independent follow-up requires the server visitKey"
            }
            require(VISIT_KEY.matches(visitKey))
        }

        // Do not store under ODK's instances directory or Android backup.
        val root = File(privateNoBackupRoot, VAULT_DIRECTORY)
        require(root.isDirectory || root.mkdirs()) { "No private draft staging directory" }
        val project = File(root, projectId)
        require(project.isDirectory || project.mkdir()) { "Cannot scope draft to project" }
        require(project.canonicalFile.parentFile == root.canonicalFile) {
            "Unexpected project directory escape"
        }
        val workDir = File(project, prepared.workId)
        val previous = if (workDir.exists()) readInternal(workDir) else null
        val proposed = QuarantinedWorkDraft(
            projectId = projectId,
            actorContextSha256 = actorContextSha256,
            workId = prepared.workId,
            sourceSubmissionId = prepared.sourceSubmissionId,
            sourceInstanceId = sourceInstanceId,
            targetFormVersionId = prepared.targetFormVersionId,
            targetFormId = prepared.targetFormId,
            targetVersion = prepared.targetFormVersion,
            workType = prepared.type,
            visitKey = visitKey,
            reservedInstanceId = previous?.reservedInstanceId
                ?: "uuid:" + UUID.randomUUID().toString(),
            sourceXmlSha256 = assessment.sourceSha256,
            sourceXFormSha256 = plan.sourceXFormSha256,
            targetXFormSha256 = plan.targetXFormSha256,
            reviewedPaths = plan.candidates.size,
            validationTransfers = expectedTransfers,
            requiresDeprecatedId = prepared.type == RecordWorkType.AMENDMENT
        )
        if (previous != null) {
            require(previous == proposed) {
                "An existing quarantined work item changed; never overwrite its identity"
            }
            return previous
        }

        val temp = File(project, ".pending-" + UUID.randomUUID())
        require(temp.mkdir()) { "Cannot create draft staging transaction" }
        try {
            val manifest = File(temp, MANIFEST)
            val fields = propertiesOf(proposed)
            val digest = digestOf(fields)
            fields.setProperty("integritySha256", digest)
            FileOutputStream(manifest).use { stream ->
                fields.store(stream, "Inert Data Collector record work binding; no answer data")
                stream.fd.sync()
            }
            require(temp.renameTo(workDir)) {
                "Draft staging collision or incomplete atomic directory move"
            }
            return proposed
        } finally {
            if (temp.exists()) temp.deleteRecursively()
        }
    }

    @Synchronized
    fun read(projectId: String, workId: String, actorContextSha256: String):
        QuarantinedWorkDraft? {
        require(PROJECT_KEY.matches(projectId) && DIGEST.matches(actorContextSha256))
        checkUuid(workId)
        val folder = File(File(File(privateNoBackupRoot, VAULT_DIRECTORY), projectId), workId)
        if (!folder.exists()) return null
        val value = readInternal(folder)
        require(value.projectId == projectId &&
            value.workId == workId &&
            value.actorContextSha256 == actorContextSha256) {
            "Project/user context changed; reauthorization required"
        }
        return value
    }

    private fun readInternal(dir: File): QuarantinedWorkDraft {
        require(dir.isDirectory && !java.nio.file.Files.isSymbolicLink(dir.toPath())) {
            "Symlinked or invalid draft directory"
        }
        val manifest = File(dir, MANIFEST)
        require(manifest.isFile && !java.nio.file.Files.isSymbolicLink(manifest.toPath()) &&
            manifest.length() in 1..16384) { "Missing or oversized draft manifest" }
        val p = Properties()
        manifest.inputStream().use { p.load(it) }
        val digest = p.getProperty("integritySha256") ?: error("Missing binding digest")
        p.remove("integritySha256")
        require(digest == digestOf(p)) { "Quarantined draft manifest was corrupted" }
        fun v(key: String) = requireNotNull(p.getProperty(key)) {
            "Missing quarantined metadata: " + key
        }
        val x = QuarantinedWorkDraft(
            projectId = v("projectId"),
            actorContextSha256 = v("actorContextSha256"),
            workId = v("workId"),
            sourceSubmissionId = v("sourceSubmissionId"),
            sourceInstanceId = v("sourceInstanceId"),
            targetFormVersionId = v("targetFormVersionId"),
            targetFormId = v("targetFormId"),
            targetVersion = v("targetVersion"),
            workType = RecordWorkType.valueOf(v("workType")),
            visitKey = v("visitKey").ifBlank { null },
            reservedInstanceId = v("reservedInstanceId"),
            sourceXmlSha256 = v("sourceXmlSha256"),
            sourceXFormSha256 = v("sourceXFormSha256").ifBlank { null },
            targetXFormSha256 = v("targetXFormSha256"),
            reviewedPaths = v("reviewedPaths").toInt(),
            validationTransfers = v("validationTransfers").toInt(),
            requiresDeprecatedId = v("requiresDeprecatedId").toBooleanStrict()
        )
        require(PROJECT_KEY.matches(x.projectId) &&
            DIGEST.matches(x.actorContextSha256) &&
            DIGEST.matches(x.sourceXmlSha256) &&
            DIGEST.matches(x.targetXFormSha256) &&
            (x.sourceXFormSha256 == null || DIGEST.matches(x.sourceXFormSha256)) &&
            x.reviewedPaths >= 0 && x.validationTransfers in 0..x.reviewedPaths &&
            x.requiresDeprecatedId == (x.workType == RecordWorkType.AMENDMENT)) {
            "Invalid quarantined work properties"
        }
        listOf(x.workId, x.sourceSubmissionId, x.targetFormVersionId).forEach(::checkUuid)
        require(x.reservedInstanceId.startsWith("uuid:") &&
            x.sourceInstanceId.startsWith("uuid:"))
        checkUuid(x.reservedInstanceId.removePrefix("uuid:"))
        checkUuid(x.sourceInstanceId.removePrefix("uuid:"))
        require(x.reservedInstanceId != x.sourceInstanceId)
        return x
    }

    private fun propertiesOf(x: QuarantinedWorkDraft) = Properties().apply {
        val all = mapOf(
            "projectId" to x.projectId,
            "actorContextSha256" to x.actorContextSha256,
            "workId" to x.workId,
            "sourceSubmissionId" to x.sourceSubmissionId,
            "sourceInstanceId" to x.sourceInstanceId,
            "targetFormVersionId" to x.targetFormVersionId,
            "targetFormId" to x.targetFormId,
            "targetVersion" to x.targetVersion,
            "workType" to x.workType.name,
            "visitKey" to (x.visitKey ?: ""),
            "reservedInstanceId" to x.reservedInstanceId,
            "sourceXmlSha256" to x.sourceXmlSha256,
            "sourceXFormSha256" to (x.sourceXFormSha256 ?: ""),
            "targetXFormSha256" to x.targetXFormSha256,
            "reviewedPaths" to x.reviewedPaths.toString(),
            "validationTransfers" to x.validationTransfers.toString(),
            "requiresDeprecatedId" to x.requiresDeprecatedId.toString()
        )
        all.forEach { (key, value) -> setProperty(key, value) }
    }

    private fun digestOf(properties: Properties): String =
        sha256(properties.stringPropertyNames().sorted()
            .joinToString("\n") { key -> key + "=" + properties.getProperty(key) })

    companion object {
        const val VAULT_DIRECTORY = "recordwork-drafts"
        private const val MANIFEST = "binding.properties"
        private val PROJECT_KEY = Regex("[A-Za-z0-9_-]{1,128}")
        private val VISIT_KEY = Regex("[A-Za-z0-9._:-]{1,128}")
        private val DIGEST = Regex("[0-9a-f]{64}")
        private val UUID_TEXT = Regex(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"
        )
        private fun checkUuid(value: String) {
            require(UUID_TEXT.matches(value)) { "Not a canonical UUID" }
        }
        fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}
