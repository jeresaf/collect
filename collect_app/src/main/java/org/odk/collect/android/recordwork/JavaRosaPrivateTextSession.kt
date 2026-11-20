package org.odk.collect.android.recordwork

import org.javarosa.core.model.Constants
import org.javarosa.core.model.FormDef
import org.javarosa.core.model.data.StringData
import org.javarosa.core.model.instance.InstanceInitializationFactory
import org.javarosa.core.model.instance.TreeReference
import org.javarosa.form.api.FormEntryController
import org.javarosa.form.api.FormEntryModel
import org.odk.collect.android.formentry.FormDefCache
import org.odk.collect.android.formentry.FormEntryUseCases
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/**
 * M4C-2B-3B1: a deliberately narrow, real-JavaRosa-backed private text editor.
 * No ODK Instance is created and no source XML is imported under a newer form.
 * Forms containing branching, media, selects, calculations or repeats are
 * refused rather than partially interpreted.
 */
class JavaRosaPrivateTextSession(
    private val scratchRoot: File,
    val binding: QuarantinedWorkDraft,
    val plan: HistoricalMappingPlan,
    private val targetXFormXml: String
) {
    val fields: List<String>

    init {
        require(binding.state == QuarantinedWorkDraft.STATE_QUARANTINED &&
            !binding.canOpenEditor && !binding.canFinalize && !binding.canUpload) {
            "Private text editing cannot grant native ODK edit/send permissions"
        }
        require(binding.workId == plan.workId &&
            binding.sourceSubmissionId == plan.sourceSubmissionId &&
            binding.targetFormVersionId == plan.targetFormVersionId &&
            binding.targetXFormSha256 == plan.targetXFormSha256 &&
            binding.targetFormId == plan.targetFormId &&
            binding.targetVersion == plan.targetVersion &&
            binding.workType == plan.workType &&
            binding.sourceXFormSha256 == plan.sourceXFormSha256 &&
            sha256(targetXFormXml) == binding.targetXFormSha256) {
            "Private draft form version or work identity changed"
        }
        require(plan.candidates.isNotEmpty() && plan.candidates.size <= MAX_FIELDS &&
            plan.candidates.map { it.targetPath }.toSet().size == plan.candidates.size) {
            "Target questionnaire exceeds private text editor limits"
        }
        require(plan.candidates.all { candidate ->
            candidate.targetPath.startsWith("/" + binding.targetFormId + "/") &&
                candidate.targetPath.removePrefix("/").split("/").all { SAFE_NAME.matches(it) } &&
                candidate.risks.intersect(UNSUPPORTED).isEmpty()
        }) { "Questionnaire requires native ODK widgets; text-only editing denied" }
        require(targetXFormXml.length <= MAX_FORM_XML &&
            !targetXFormXml.contains("<!DOCTYPE", ignoreCase = true) &&
            !targetXFormXml.contains("<!ENTITY", ignoreCase = true) &&
            !EXTERNAL_SRC.containsMatchIn(targetXFormXml)) {
            "Unsupported or unsafe target form"
        }

        fields = plan.candidates.map { it.targetPath }
        // Check the *native* type of every target path upfront.
        withNativeForm { def ->
            for (path in fields) {
                val node = def.instance.resolveReference(ref(path))
                    ?: throw IllegalArgumentException("Target text question missing")
                require(node.isLeaf && node.dataType == Constants.DATATYPE_TEXT) {
                    "Non-text JavaRosa question requires a specialized editor"
                }
            }
        }
    }

    /**
     * Rebuilds an independent blank target FormDef for each validation.
     * No partial state is persisted if one of the text answers is invalid.
     * Blank questions are permitted for *drafts*, never finalization.
     */
    fun validateForPrivateSave(answers: Map<String, String>): Map<String, String> {
        require(answers.keys.all { it in fields } && answers.size <= fields.size) {
            "Answer targets an unknown/unreviewed question"
        }
        require(answers.values.all { it.length <= MAX_TEXT &&
            it.none { ch -> ch == '\u0000' } }) {
            "Text answer is too large or invalid"
        }
        return withNativeForm { def ->
            val clean = linkedMapOf<String, String>()
            for (path in fields) {
                val value = answers[path].orEmpty()
                val reference = ref(path)
                val node = requireNotNull(def.instance.resolveReference(reference)) {
                    "Approved target question missing"
                }
                require(node.isLeaf && node.dataType == Constants.DATATYPE_TEXT) {
                    "Question changed to a non-text type"
                }
                if (value.isNotEmpty()) {
                    val data = StringData(value)
                    require(def.evaluateConstraint(reference, data)) {
                        "Target JavaRosa rules rejected the entered answer"
                    }
                    def.setValue(data, reference, true)
                    require(def.instance.resolveReference(reference)?.value?.value == value) {
                        "JavaRosa did not retain the entered target text"
                    }
                }
                clean[path] = value
            }
            clean.toMap()
        }
    }

    private fun <T> withNativeForm(block: (FormDef) -> T): T {
        require(scratchRoot.isDirectory || scratchRoot.mkdirs()) {
            "Private JavaRosa scratch directory not available"
        }
        val folder = File(scratchRoot, "recordwork-text-" + UUID.randomUUID())
        require(folder.mkdir()) { "Unable to isolate form definition" }
        try {
            val xFormFile = File(folder, "target.xml")
            xFormFile.writeText(targetXFormXml, Charsets.UTF_8)
            val noCache = object : FormDefCache {
                override fun readCache(formXml: File?): FormDef? = null
                override fun writeCache(formDef: FormDef?, formPath: String?) = Unit
            }
            val def = FormEntryUseCases.createFormDefFromCacheOrXml(xFormFile, noCache)
                ?: throw IllegalArgumentException("JavaRosa rejected target version")
            val controller = FormEntryController(FormEntryModel(def))
            controller.model.form.initialize(true, InstanceInitializationFactory())
            require(def.instance.root.name == binding.targetFormId) {
                "JavaRosa root differs from authorized server form"
            }
            return block(def)
        } finally {
            folder.deleteRecursively()
        }
    }

    private fun ref(path: String): TreeReference {
        val segments = path.removePrefix("/").split("/")
        require(segments.size in 2..12 && segments.first() == binding.targetFormId &&
            segments.all { SAFE_NAME.matches(it) }) {
            "Unsafe target field reference"
        }
        return TreeReference.rootRef().apply {
            segments.forEach { add(it, 0) }
        }
    }

    companion object {
        const val MAX_FIELDS = 30
        const val MAX_TEXT = 16_384
        private const val MAX_FORM_XML = 8_388_608
        private val SAFE_NAME = Regex("[A-Za-z_][A-Za-z0-9_.-]*")
        private val EXTERNAL_SRC = Regex("""\bsrc\s*=\s*["']""", RegexOption.IGNORE_CASE)
        private val UNSUPPORTED = setOf(
            MappingRisk.REPEAT, MappingRisk.MEDIA, MappingRisk.STATIC_SELECT,
            MappingRisk.DYNAMIC_SELECT, MappingRisk.CHOICE_LIST_CHANGED,
            MappingRisk.CALCULATION, MappingRisk.RELEVANCE_OR_CONSTRAINT,
            MappingRisk.UNBOUND_FIELD, MappingRisk.TYPE_CHANGED
        )
        fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}
