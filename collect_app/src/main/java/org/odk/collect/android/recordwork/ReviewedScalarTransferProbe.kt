package org.odk.collect.android.recordwork

import org.javarosa.core.model.Constants
import org.javarosa.core.model.FormDef
import org.javarosa.core.model.data.StringData
import org.javarosa.core.model.instance.TreeElement
import org.javarosa.core.model.instance.TreeReference
import org.javarosa.core.model.instance.InstanceInitializationFactory
import org.javarosa.form.api.FormEntryController
import org.javarosa.form.api.FormEntryModel
import org.javarosa.xform.parse.XFormParser
import org.odk.collect.android.formentry.FormDefCache
import org.odk.collect.android.formentry.FormEntryUseCases
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/**
 * Executes individually reviewed, unchanged STRING transfers in a transient
 * JavaRosa model. Does not return answers, persist a draft or enable upload.
 */
data class ReviewedTransferEvidence(
    val workId: String,
    val sourceSubmissionId: String,
    val targetFormVersionId: String,
    val workType: RecordWorkType,
    val sourceInstanceId: String,
    val sourceXmlSha256: String,
    val sourceXFormSha256: String,
    val targetXFormSha256: String,
    val pathsReviewed: Int,
    val pathsTransferred: Int,
    val pathsToReenter: Int,
    val pathsLeftEmpty: Int,
    val canSaveDraft: Boolean = false,
    val canSubmit: Boolean = false
) {
    init {
        require(!canSaveDraft && !canSubmit) {
            "Ephemeral JavaRosa validation cannot authorize draft or submission"
        }
    }
}

class ReviewedScalarTransferProbe(private val privateCacheDirectory: File) {
    /**
     * All inputs must be bound to one authenticated READY work item's prepare
     * response. expectedSourceXmlSha256 pins the exact original response.
     */
    fun validate(
        plan: HistoricalMappingPlan,
        review: MappingReviewSnapshot,
        sourceXml: String,
        expectedSourceXmlSha256: String,
        expectedSourceInstanceId: String,
        sourceXFormXml: String?,
        targetXFormXml: String
    ): ReviewedTransferEvidence {
        require(plan.workType == RecordWorkType.AMENDMENT) {
            "Periodic follow-ups are separate observations without copying"
        }
        require(!sourceXFormXml.isNullOrBlank() && plan.sourceXFormSha256 != null) {
            "Historical source schema is required"
        }
        require(plan.sourceXFormSha256 == sha256(requireNotNull(sourceXFormXml)) &&
            plan.targetXFormSha256 == sha256(targetXFormXml) &&
            expectedSourceXmlSha256 == sha256(sourceXml)) {
            "A prepared document changed after review"
        }
        require(plan.globalRisks.none {
            it in setOf(MappingRisk.NO_HISTORICAL_SCHEMA,
                MappingRisk.SOURCE_SCHEMA_MISMATCH, MappingRisk.DIFFERENT_FORM_FOLLOW_UP)
        }) { "Unknown or incompatible original form" }
        require(review.workId == plan.workId &&
            review.sourceXFormSha256 == plan.sourceXFormSha256 &&
            review.targetXFormSha256 == plan.targetXFormSha256) {
            "Operator review differs from the prepared work and versions"
        }
        val expectedPaths = plan.candidates.map { it.targetPath }.toSet()
        require(expectedPaths.isNotEmpty() && review.decisions.keys == expectedPaths &&
            review.reviewedEveryTargetPath) {
            "All target paths must be individually reviewed"
        }
        val transfers = plan.candidates.filter {
            review.decisions[it.targetPath] == MappingDecision.REQUEST_REVIEWED_TRANSFER
        }
        require(transfers.all {
            it.safeScalarProposal && it.risks.isEmpty() &&
                it.sourcePath == it.targetPath
        }) { "Unsafe or renamed field cannot be transferred automatically" }
        require(sourceXml.isNotBlank() && sourceXml.length <= MAX_XML &&
            expectedSourceInstanceId.startsWith("uuid:") &&
            UUID_PATTERN.matches(expectedSourceInstanceId.removePrefix("uuid:"))) {
            "Invalid original instance identifier"
        }

        // Native validation of BOTH form versions precedes any changes.
        val staging = JavaRosaStagingProbe(privateCacheDirectory)
            .inspect(plan, targetXFormXml, sourceXFormXml, review)
        require(staging.targetJavaRosaInitialized &&
            staging.historicalJavaRosaInitialized && staging.operatorPathsReviewed) {
            "Source/target JavaRosa validation or operator review is missing"
        }

        val sourceRoot = XFormParser.restoreDataModel(
            sourceXml.toByteArray(Charsets.UTF_8), null
        ).root
        require(sourceRoot.name.isNotBlank() &&
            sourceRoot.name == plan.candidates.first().targetPath
                .removePrefix("/").substringBefore("/")) {
            "Original instance root differs from reviewed target form"
        }
        val actualInstanceId = uniquePath(
            sourceRoot, "/" + sourceRoot.name + "/meta/instanceID"
        )?.value?.value as? String
        require(actualInstanceId == expectedSourceInstanceId) {
            "Original instance ID does not match server work item"
        }

        require(privateCacheDirectory.isDirectory || privateCacheDirectory.mkdirs()) {
            "Private JavaRosa staging cache unavailable"
        }
        val scratch = File(privateCacheDirectory, "recordwork-transfer-" + UUID.randomUUID())
        require(scratch.mkdir()) { "Cannot create private transfer workspace" }
        try {
            val formFile = File(scratch, "target.xml")
            formFile.writeText(targetXFormXml, Charsets.UTF_8)
            val uncached = object : FormDefCache {
                override fun readCache(formXml: File?): FormDef? = null
                override fun writeCache(formDef: FormDef?, formPath: String?) = Unit
            }
            val target = FormEntryUseCases.createFormDefFromCacheOrXml(formFile, uncached)
                ?: throw IllegalArgumentException("Native target form load failed")
            val controller = FormEntryController(FormEntryModel(target))
            controller.model.form.initialize(true, InstanceInitializationFactory())

            var count = 0
            for (candidate in transfers) {
                val path = candidate.targetPath
                val src = uniquePath(sourceRoot, path)
                    ?: throw IllegalArgumentException("Approved original field not found")
                require(src.children.isEmpty()) { "Source is not a scalar node" }
                val text = src.value?.value as? String
                    ?: throw IllegalArgumentException("Only original text is supported")
                require(text.length <= MAX_TEXT) { "Text answer exceeds safety bound" }

                val ref = exactReference(path)
                val targetNode = target.instance.resolveReference(ref)
                    ?: throw IllegalArgumentException("Target leaf is absent")
                require(targetNode.isLeaf && targetNode.dataType == Constants.DATATYPE_TEXT) {
                    "Only native JavaRosa string answers are eligible for transfer"
                }
                val answer = StringData(text)
                require(target.evaluateConstraint(ref, answer)) {
                    "New version's constraint rejected a proposed answer"
                }
                // Proper native setValue recalculates dependencies and invokes
                // target-version JavaRosa rules, unlike direct XML assignment.
                target.setValue(answer, ref, true)
                require(targetNode.value?.value == text) {
                    "New version did not retain the reviewed answer"
                }
                count++
            }
            // Target model is discarded. No instanceID/deprecatedID is set,
            // no media or answer XML is written, no ODK draft is registered.
            return ReviewedTransferEvidence(
                workId = plan.workId,
                sourceSubmissionId = plan.sourceSubmissionId,
                targetFormVersionId = plan.targetFormVersionId,
                workType = plan.workType,
                sourceInstanceId = expectedSourceInstanceId,
                sourceXmlSha256 = expectedSourceXmlSha256,
                sourceXFormSha256 = plan.sourceXFormSha256,
                targetXFormSha256 = plan.targetXFormSha256,
                pathsReviewed = review.decisions.size,
                pathsTransferred = count,
                pathsToReenter = review.decisions.values.count {
                    it == MappingDecision.REENTER
                },
                pathsLeftEmpty = review.decisions.values.count {
                    it == MappingDecision.LEAVE_EMPTY
                }
            )
        } finally {
            scratch.deleteRecursively()
        }
    }

    private fun uniquePath(root: TreeElement, path: String): TreeElement? {
        val parts = path.removePrefix("/").split("/")
        require(parts.size >= 2 && parts.size <= 80 &&
            parts.first() == root.name && parts.all { SAFE_FIELD.matches(it) }) {
            "Unsafe source reference"
        }
        var node = root
        for (part in parts.drop(1)) {
            val matches = node.children.filter { it.name == part }
            require(matches.size <= 1) { "Ambiguous source repeat path" }
            node = matches.singleOrNull() ?: return null
        }
        return node
    }

    private fun exactReference(path: String) = TreeReference.rootRef().apply {
        val parts = path.removePrefix("/").split("/")
        require(parts.size in 2..80 && parts.all { SAFE_FIELD.matches(it) }) {
            "Unsafe JavaRosa target reference"
        }
        parts.forEach { add(it, 0) }
    }

    companion object {
        private const val MAX_XML = 8_388_608
        private const val MAX_TEXT = 16_384
        private val SAFE_FIELD = Regex("[A-Za-z_][A-Za-z0-9_.-]*")
        private val UUID_PATTERN = Regex(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"
        )
        fun sha256(text: String): String = MessageDigest.getInstance("SHA-256")
            .digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}
