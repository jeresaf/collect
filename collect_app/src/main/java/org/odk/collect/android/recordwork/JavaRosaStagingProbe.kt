package org.odk.collect.android.recordwork

import org.javarosa.core.model.FormDef
import org.javarosa.core.model.instance.InstanceInitializationFactory
import org.javarosa.form.api.FormEntryController
import org.javarosa.form.api.FormEntryModel
import org.odk.collect.android.formentry.FormDefCache
import org.odk.collect.android.formentry.FormEntryUseCases
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/**
 * M4C-2B-1: native JavaRosa parsing and blank-form initialization under the
 * exact selected version. This is staging evidence, NOT an editable ODK draft.
 * Importing a previous XML instance under an arbitrary newer FormDef can
 * silently corrupt answers. Never call loadDraft, saveDraft, or the uploader.
 */
data class NativeStagingEvidence(
    val workId: String,
    val sourceSubmissionId: String,
    val targetFormVersionId: String,
    val workType: RecordWorkType,
    val sourceXFormSha256: String?,
    val targetXFormSha256: String,
    val targetJavaRosaInitialized: Boolean,
    val historicalJavaRosaInitialized: Boolean,
    val compatibleScalarCandidates: Int,
    val operatorPathsReviewed: Boolean,
    val canImportAnswers: Boolean = false,
    val canCreateDraft: Boolean = false,
    val canSubmit: Boolean = false
) {
    init {
        require(!canImportAnswers && !canCreateDraft && !canSubmit) {
            "A native parser check cannot authorize editing or upload"
        }
    }
}

/**
 * Requires source and target XML to have passed M4C-1/2A hardened XML and
 * exact-version checks. The isolated app-private directory holds ONLY
 * temporary XForm definitions, never actual answers or instance XML.
 *
 * External form resource resolution is unsupported here and fails closed.
 */
class JavaRosaStagingProbe(private val privateCacheDirectory: File) {
    fun inspect(
        plan: HistoricalMappingPlan,
        targetXFormXml: String,
        sourceXFormXml: String?,
        review: MappingReviewSnapshot? = null
    ): NativeStagingEvidence {
        require(plan.targetXFormSha256 == sha256(targetXFormXml)) {
            "Target XForm changed after path review"
        }
        require(plan.sourceXFormSha256 ==
            sourceXFormXml?.takeIf(String::isNotBlank)?.let(::sha256)) {
            "Historical XForm changed after path review"
        }
        if (review != null) {
            require(review.workId == plan.workId &&
                review.sourceXFormSha256 == plan.sourceXFormSha256 &&
                review.targetXFormSha256 == plan.targetXFormSha256) {
                "Review belongs to another work order or form version"
            }
            require(review.decisions.keys.all { path ->
                plan.candidates.any { it.targetPath == path }
            }) { "Unknown reviewed target field" }
            require(review.decisions.all { (path, decision) ->
                decision != MappingDecision.REQUEST_REVIEWED_TRANSFER ||
                    plan.candidates.single { it.targetPath == path }.safeScalarProposal
            }) { "Unsafe proposed scalar transfer" }
        }

        require(privateCacheDirectory.isDirectory || privateCacheDirectory.mkdirs()) {
            "Private staging cache unavailable"
        }
        val scratch = File(privateCacheDirectory,
            "recordwork-probe-" + UUID.randomUUID().toString())
        require(scratch.mkdir()) { "Cannot create private scratch workspace" }
        try {
            requireSafeFormXml(targetXFormXml)
            val targetFile = File(scratch, "target.xml")
            targetFile.writeText(targetXFormXml, Charsets.UTF_8)
            val nativeTargetRoot = loadBlankWithJavaRosa(targetFile)
            val expectedRoot = plan.candidates.firstOrNull()?.targetPath
                ?.substringAfter('/').orEmpty().substringBefore('/')
            if (expectedRoot.isNotBlank()) {
                require(nativeTargetRoot == expectedRoot) {
                    "JavaRosa target root differs from path review"
                }
            }

            val historical = !sourceXFormXml.isNullOrBlank()
            if (historical) {
                requireSafeFormXml(requireNotNull(sourceXFormXml))
                val sourceFile = File(scratch, "source.xml")
                sourceFile.writeText(sourceXFormXml, Charsets.UTF_8)
                val nativeSourceRoot = loadBlankWithJavaRosa(sourceFile)
                require(nativeSourceRoot.isNotBlank()) { "Empty historical root" }
                if (plan.workType == RecordWorkType.AMENDMENT &&
                    MappingRisk.SOURCE_SCHEMA_MISMATCH in plan.globalRisks) {
                    throw IllegalArgumentException("Amendment source form mismatch")
                }
            }
            return NativeStagingEvidence(
                workId = plan.workId,
                sourceSubmissionId = plan.sourceSubmissionId,
                targetFormVersionId = plan.targetFormVersionId,
                workType = plan.workType,
                sourceXFormSha256 = plan.sourceXFormSha256,
                targetXFormSha256 = plan.targetXFormSha256,
                targetJavaRosaInitialized = true,
                historicalJavaRosaInitialized = historical,
                compatibleScalarCandidates =
                    plan.candidates.count { it.safeScalarProposal },
                operatorPathsReviewed = review?.reviewedEveryTargetPath == true
            )
        } finally {
            scratch.deleteRecursively()
        }
    }

    private fun loadBlankWithJavaRosa(file: File): String {
        val noCache = object : FormDefCache {
            override fun readCache(formXml: File?): FormDef? = null
            override fun writeCache(formDef: FormDef?, formPath: String?) = Unit
        }
        val def = FormEntryUseCases.createFormDefFromCacheOrXml(file, noCache)
            ?: throw IllegalArgumentException("Native JavaRosa rejected XForm")
        val controller = FormEntryController(FormEntryModel(def))
        // Evaluate/initialize a blank target model, never populate from
        // previous answers and never register it in InstancesRepository.
        controller.model.form.initialize(true, InstanceInitializationFactory())
        return controller.model.form.instance.root.name.orEmpty()
    }

    private fun requireSafeFormXml(xml: String) {
        require(xml.isNotBlank() && xml.length <= MAX_XML_SIZE) { "Unsafe XML size" }
        require(!xml.contains("<!DOCTYPE", ignoreCase = true) &&
            !xml.contains("<!ENTITY", ignoreCase = true)) { "DTD/ENTITY forbidden" }
        require(!EXTERNAL_SRC.containsMatchIn(xml)) {
            "External XForm sources require separate media/resolver qualification"
        }
    }

    companion object {
        private const val MAX_XML_SIZE = 8_388_608
        private val EXTERNAL_SRC = Regex("""\bsrc\s*=\s*["']""", RegexOption.IGNORE_CASE)
        private fun sha256(data: String): String = MessageDigest.getInstance("SHA-256")
            .digest(data.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}
