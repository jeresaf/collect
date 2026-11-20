package org.odk.collect.android.recordwork

import org.javarosa.core.model.Constants
import org.javarosa.core.model.FormDef
import org.javarosa.core.model.IFormElement
import org.javarosa.core.model.QuestionDef
import org.javarosa.core.model.instance.InstanceInitializationFactory
import org.javarosa.core.model.instance.TreeReference
import org.javarosa.form.api.FormEntryController
import org.javarosa.form.api.FormEntryModel
import org.odk.collect.android.formentry.FormDefCache
import org.odk.collect.android.formentry.FormEntryUseCases
import java.io.File
import java.util.UUID

/**
 * Checks an exact authorized target-version XForm using actual JavaRosa
 * upload controls, not the source submission, media filenames or UI guesses.
 *
 * Supports ONLY non-repeating image/* controls with a native binary leaf.
 * Refuses repeat groups and external instance resources globally because
 * media nodes in those structures need a separate identity/ownership model.
 */
class JavaRosaPrivateImageFieldProbe(
    private val scratchRoot: File,
    private val binding: QuarantinedWorkDraft,
    private val plan: HistoricalMappingPlan,
    private val targetXFormXml: String
) {
    val imagePaths: List<String>

    init {
        require(binding.projectId.isNotBlank() &&
            binding.state == QuarantinedWorkDraft.STATE_QUARANTINED &&
            !binding.canOpenEditor && !binding.canFinalize && !binding.canUpload &&
            binding.workId == plan.workId &&
            binding.sourceSubmissionId == plan.sourceSubmissionId &&
            binding.targetFormVersionId == plan.targetFormVersionId &&
            binding.targetFormId == plan.targetFormId &&
            binding.targetVersion == plan.targetVersion &&
            binding.workType == plan.workType &&
            binding.sourceXFormSha256 == plan.sourceXFormSha256 &&
            binding.targetXFormSha256 == plan.targetXFormSha256 &&
            binding.targetXFormSha256 == JavaRosaPrivateScalarSession.digest(targetXFormXml) &&
            plan.candidates.size == binding.reviewedPaths) {
            "Private image intake must remain bound to exact authorized work"
        }
        require(targetXFormXml.isNotBlank() && targetXFormXml.length <= 8_388_608 &&
            !targetXFormXml.contains("<!DOCTYPE", ignoreCase = true) &&
            !targetXFormXml.contains("<!ENTITY", ignoreCase = true) &&
            !EXTERNAL_SOURCE.containsMatchIn(targetXFormXml) &&
            !REPEAT_OR_DYNAMIC.containsMatchIn(targetXFormXml)) {
            "Repeating or external-resource media is not supported"
        }
        val candidates = plan.candidates.filter { MappingRisk.MEDIA in it.risks }
        require(candidates.isNotEmpty() && candidates.size <= MAX_IMAGES &&
            candidates.map { it.targetPath }.toSet().size == candidates.size &&
            candidates.all { MappingRisk.REPEAT !in it.risks &&
                MappingRisk.CALCULATION !in it.risks &&
                MappingRisk.RELEVANCE_OR_CONSTRAINT !in it.risks &&
                MappingRisk.UNBOUND_FIELD !in it.risks }) {
            "Media paths require individual non-repeating review"
        }
        val root = candidates.first().targetPath.removePrefix("/").substringBefore("/")
        require(NAME.matches(root) && candidates.all { candidate ->
            candidate.targetPath.startsWith("/" + root + "/") &&
                candidate.targetPath.removePrefix("/").split("/").all(NAME::matches)
        }) { "Invalid reviewed image field path" }

        imagePaths = withForm { def ->
            require(def.instance.root.name == root)
            val discovered = linkedMapOf<String, QuestionDef>()
            fun scan(controls: List<IFormElement>?) {
                controls.orEmpty().forEach { control ->
                    if (control is QuestionDef) {
                        val pathRef = control.bind?.reference as? TreeReference
                            ?: throw IllegalArgumentException("Unbound native control")
                        require(pathRef.size() in 2..12 &&
                            (0 until pathRef.size()).all { i ->
                                pathRef.getMultiplicity(i) == 0 ||
                                    pathRef.getMultiplicity(i) == TreeReference.INDEX_UNBOUND
                            }) { "Repeating media reference is not supported" }
                        val path = "/" + (0 until pathRef.size())
                            .joinToString("/") { i -> pathRef.getName(i) }
                        require(path !in discovered) { "Duplicate native media question" }
                        discovered[path] = control
                    } else {
                        scan(control.children)
                    }
                }
            }
            scan(def.children)

            candidates.map { candidate ->
                val question = requireNotNull(discovered[candidate.targetPath]) {
                    "Media field is absent from the target native FormDef"
                }
                require(question.controlType == Constants.CONTROL_IMAGE_CHOOSE &&
                    question.dynamicChoices == null) {
                    "Only JavaRosa image/* upload controls may import private images"
                }
                val node = requireNotNull(
                    def.instance.resolveReference(toRef(candidate.targetPath))
                ) { "Native image leaf is absent" }
                require(node.isLeaf && node.dataType == Constants.DATATYPE_BINARY) {
                    "Target media field must be a native binary leaf"
                }
                candidate.targetPath
            }
        }
    }

    private fun toRef(path: String): TreeReference =
        TreeReference.rootRef().apply {
            path.removePrefix("/").split("/").forEach { add(it, 0) }
        }

    private fun <T> withForm(block: (FormDef) -> T): T {
        require(scratchRoot.isDirectory || scratchRoot.mkdirs()) {
            "No isolated JavaRosa temporary directory"
        }
        val folder = File(scratchRoot, "recordwork-image-" + UUID.randomUUID())
        require(folder.mkdir()) { "Cannot create isolated media schema workspace" }
        try {
            val file = File(folder, "target.xml")
            file.writeText(targetXFormXml, Charsets.UTF_8)
            val noCache = object : FormDefCache {
                override fun readCache(formXml: File?): FormDef? = null
                override fun writeCache(formDef: FormDef?, formPath: String?) = Unit
            }
            val def = requireNotNull(
                FormEntryUseCases.createFormDefFromCacheOrXml(file, noCache)
            ) { "JavaRosa rejected this target XForm" }
            FormEntryController(FormEntryModel(def))
            def.initialize(true, InstanceInitializationFactory())
            return block(def)
        } finally {
            folder.deleteRecursively()
        }
    }

    companion object {
        const val MAX_IMAGES = 4
        private val NAME = Regex("[A-Za-z_][A-Za-z0-9_.-]*")
        private val REPEAT_OR_DYNAMIC = Regex(
            """<\s*(?:[A-Za-z_][\w.-]*:)?(?:repeat|itemset|action|setvalue)\b""",
            RegexOption.IGNORE_CASE
        )
        private val EXTERNAL_SOURCE = Regex(
            """\bsrc\s*=\s*["']""", RegexOption.IGNORE_CASE
        )
    }
}
