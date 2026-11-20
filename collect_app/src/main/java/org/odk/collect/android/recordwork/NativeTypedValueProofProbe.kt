package org.odk.collect.android.recordwork

import org.javarosa.core.model.instance.TreeElement
import org.javarosa.xform.parse.XFormParser
import java.io.File

/**
 * 3B-3B — native historical typed-value qualification WITHOUT a transfer.
 * No historical answer is returned by this API or saved anywhere.
 */
data class TypedValueProof(
    val workId: String,
    val sourceXmlSha256: String,
    val verifiedPaths: List<String>,
    val canCopy: Boolean = false,
    val canWriteDraft: Boolean = false,
    val canSubmit: Boolean = false
) {
    init {
        require(!canCopy && !canWriteDraft && !canSubmit) {
            "Native value validation is not consent or transfer authorization"
        }
    }
}

/**
 * This component must only be called after a fresh server-authorized source
 * fetch and a separately confirmed, encrypted operator mapping review.
 * Existing text-only import code is not modified. The returned proof contains
 * PATHS ONLY, no values, XML, or media.
 *
 * Fail closed for changed types, non-canonical values, choice drift, repeats,
 * missing values, follow-ups, unsupported controls, and schema identity drift.
 */
class NativeTypedValueProofProbe(private val privateCache: File) {
    fun inspect(
        binding: QuarantinedWorkDraft,
        source: AuthorizedReviewedSource,
        reviewed: TypedTransferPreflight
    ): TypedValueProof {
        val plan = source.plan
        require(binding.workType == RecordWorkType.AMENDMENT &&
            plan.workType == RecordWorkType.AMENDMENT &&
            reviewed.workId == binding.workId &&
            plan.workId == binding.workId &&
            plan.sourceSubmissionId == binding.sourceSubmissionId &&
            plan.targetFormVersionId == binding.targetFormVersionId &&
            plan.targetFormId == binding.targetFormId &&
            plan.targetVersion == binding.targetVersion &&
            plan.sourceXFormSha256 == binding.sourceXFormSha256 &&
            plan.targetXFormSha256 == binding.targetXFormSha256 &&
            binding.sourceXFormSha256 != null &&
            reviewed.sourceXFormSha256 == binding.sourceXFormSha256 &&
            reviewed.targetXFormSha256 == binding.targetXFormSha256 &&
            reviewed.sourceXmlSha256 == binding.sourceXmlSha256 &&
            ReviewedScalarTransferProbe.sha256(source.sourceXml) ==
                binding.sourceXmlSha256 &&
            ReviewedScalarTransferProbe.sha256(source.sourceXFormXml) ==
                binding.sourceXFormSha256 &&
            ReviewedScalarTransferProbe.sha256(source.targetXFormXml) ==
                binding.targetXFormSha256 &&
            !reviewed.canReadHistoricalAnswers && !reviewed.canTransfer &&
            !reviewed.canFinalize && !reviewed.canUpload &&
            reviewed.candidatePaths.size == reviewed.pathsRequestingSeparateReview &&
            reviewed.candidatePaths.size in 1..30 &&
            reviewed.candidatePaths.toSet().size == reviewed.candidatePaths.size) {
            "Historical review, work identity or exact schemas changed"
        }
        // Both exact XForms are parsed independently in private JavaRosa
        // sessions. No historical values are written into these forms.
        val findings = NativeTypedMappingProbe(privateCache)
            .inspect(plan, source.sourceXFormXml, source.targetXFormXml)
        val allowed = findings.filter {
            it.verdict == TypedMappingVerdict.REVIEW_SAME_TYPE
        }.associateBy { it.targetPath }
        require(reviewed.candidatePaths.all { it in allowed } &&
            reviewed.candidatePaths.all { path ->
                plan.candidates.singleOrNull { it.targetPath == path }
                    ?.sourcePath == path
            }) {
            "Historical typed candidate is not an exact native same-path field"
        }

        val root = XFormParser.restoreDataModel(
            source.sourceXml.toByteArray(Charsets.UTF_8), null
        ).root
        val originalInstanceId = exactLeaf(root,
            "/" + root.name + "/meta/instanceID")?.value?.value as? String
        require(root.name == plan.candidates.first().targetPath
                .removePrefix("/").substringBefore("/") &&
            originalInstanceId == binding.sourceInstanceId) {
            "Original instance ID or root differs from the protected work"
        }
        val native = JavaRosaPrivateScalarSession(
            privateCache, binding, plan, source.targetXFormXml
        )
        require(native.fields == plan.candidates.map { it.targetPath }) {
            "Target native question inventory changed"
        }
        val validatedPaths = mutableListOf<String>()
        for (path in reviewed.candidatePaths) {
            val kind = requireNotNull(allowed[path]).nativeType
            require(native.questions.single { it.path == path }.type == kind) {
                "Native target field type changed"
            }
            val element = requireNotNull(exactLeaf(root, path)) {
                "Approved historical field was removed"
            }
            val value = requireNotNull(element.value?.value) {
                "Historical typed answer is missing"
            }
            // restoreDataModel exposes lexical XML answers; it does NOT
            // deserialize every numeric/date/choice node to a typed object.
            // Source and target JavaRosa *schemas* already agree on type.
            // The target JavaRosa form parses and validates this lexical value.
            val canonical = value as? String
                ?: throw IllegalArgumentException("Historical XML scalar is not text")
            require(canonical.isNotBlank() &&
                canonical.length <= JavaRosaPrivateScalarSession.MAX_TEXT &&
                '\u0000' !in canonical) { "Invalid historical canonical value" }
            val readback = native.validateForPrivateSave(mapOf(path to canonical))
            require(readback[path]?.isNotEmpty() == true) {
                "Target native JavaRosa rejected historical typed value"
            }
            // Never return, persist, or log 'canonical' or 'readback'.
            validatedPaths += path
        }
        return TypedValueProof(
            binding.workId, binding.sourceXmlSha256, validatedPaths.toList()
        )
    }

    private fun exactLeaf(root: TreeElement, path: String): TreeElement? {
        val parts = path.removePrefix("/").split("/")
        require(parts.size in 2..12 && parts.first() == root.name &&
            parts.all { it.matches(Regex("[A-Za-z_][A-Za-z0-9_.-]*")) }) {
            "Unsafe historical path"
        }
        var node = root
        for (part in parts.drop(1)) {
            val children = node.getChildrenWithName(part)
            require(children.size <= 1) { "Repeated historical path is ambiguous" }
            node = children.singleOrNull() ?: return null
        }
        require(node.isLeaf) { "Historical value is not a single scalar" }
        return node
    }
}
