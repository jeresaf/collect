package org.odk.collect.android.recordwork

import org.javarosa.core.model.instance.TreeElement
import org.javarosa.xform.parse.XFormParser
import java.io.File

/**
 * Explicit, one-shot historical answer proposal for a NEW target-version
 * private draft. Only exact unchanged same-path TEXT leaves with empty risks
 * may be proposed, and only after a durable operator review, current READY
 * server reauthorization, and native validation of BOTH form versions.
 *
 * This method does not write a draft, alter the original submission, create
 * an ODK Instance, or authorize send. The caller must additionally enforce
 * "save-if-absent" to avoid overwriting the user's already entered values.
 */
class ApprovedHistoricalTextImporter(private val scratchRoot: File) {
    fun validateAndPropose(
        binding: QuarantinedWorkDraft,
        approved: ProtectedMappingApproval,
        source: AuthorizedReviewedSource,
        native: JavaRosaPrivateScalarSession
    ): Map<String, String> {
        val plan = source.plan
        require(binding.workType == RecordWorkType.AMENDMENT &&
            binding.validationTransfers in 1..MAX_TRANSFER &&
            binding.workId == plan.workId &&
            binding.sourceSubmissionId == plan.sourceSubmissionId &&
            binding.targetFormVersionId == plan.targetFormVersionId &&
            binding.targetXFormSha256 == plan.targetXFormSha256 &&
            binding.sourceXFormSha256 == plan.sourceXFormSha256 &&
            approved.workId == binding.workId &&
            approved.sourceXmlSha256 == binding.sourceXmlSha256 &&
            approved.targetXFormSha256 == binding.targetXFormSha256 &&
            approved.approvedTransferPaths.size == binding.validationTransfers &&
            approved.decisions.keys == plan.candidates.map {
                it.targetPath
            }.toSet() &&
            ReviewedScalarTransferProbe.sha256(source.sourceXml) ==
                binding.sourceXmlSha256) {
            "Approval, historical data and READY work identity no longer match"
        }
        val review = MappingReviewSnapshot(
            binding.workId, binding.sourceXFormSha256,
            binding.targetXFormSha256, approved.decisions,
            reviewedEveryTargetPath = true
        )
        val evidence = ReviewedScalarTransferProbe(scratchRoot).validate(
            plan, review, source.sourceXml, binding.sourceXmlSha256,
            binding.sourceInstanceId, source.sourceXFormXml,
            source.targetXFormXml
        )
        require(evidence.pathsTransferred == binding.validationTransfers &&
            !evidence.canSaveDraft && !evidence.canSubmit) {
            "Native reviewed historical text-transfer proof is incomplete"
        }
        val original = XFormParser.restoreDataModel(
            source.sourceXml.toByteArray(Charsets.UTF_8), null
        ).root
        val proposed = linkedMapOf<String, String>()
        for (field in plan.candidates) {
            if (approved.decisions[field.targetPath] !=
                MappingDecision.REQUEST_REVIEWED_TRANSFER) continue
            require(field.safeScalarProposal &&
                field.risks.isEmpty() &&
                field.sourcePath == field.targetPath &&
                native.questions.any {
                    it.path == field.targetPath &&
                        it.type == PrivateScalarType.TEXT
                }) {
                "Historical type/choice/repeat/media change cannot be copied"
            }
            val value = exactUniqueLeaf(original, field.targetPath)
                ?.value?.value as? String
                ?: throw IllegalArgumentException(
                    "Previously approved original text answer is missing"
                )
            require(value.length <= JavaRosaPrivateScalarSession.MAX_TEXT &&
                '\u0000' !in value) {
                "Historical text exceeds safe private draft limit"
            }
            proposed[field.targetPath] = value
        }
        require(proposed.keys == approved.approvedTransferPaths) {
            "Historical text inventory differs from explicit decisions"
        }
        val blankTarget = native.fields.associateWith { path ->
            proposed[path].orEmpty()
        }
        // Native target form is built afresh, validates all proposed answers,
        // reads back each target-version value, and discards its scratch model.
        return native.validateForPrivateSave(blankTarget).also { values ->
            require(values.keys == native.fields.toSet() &&
                proposed.all { (path, value) -> values[path] == value }) {
                "Native target changed the approved original text answer"
            }
        }
    }

    private fun exactUniqueLeaf(root: TreeElement, path: String): TreeElement? {
        val parts = path.removePrefix("/").split("/")
        require(parts.size in 2..12 && parts.first() == root.name &&
            parts.all { NAME.matches(it) }) {
            "Unsafe original source path"
        }
        var node = root
        for (name in parts.drop(1)) {
            val matches = node.getChildrenWithName(name)
            require(matches.size <= 1) {
                "Repeated original nodes cannot be auto-migrated"
            }
            node = matches.singleOrNull() ?: return null
        }
        require(node.isLeaf) { "Original answer is not a scalar leaf" }
        return node
    }

    companion object {
        private const val MAX_TRANSFER = 30
        private val NAME = Regex("[A-Za-z_][A-Za-z0-9_.-]*")
    }
}
