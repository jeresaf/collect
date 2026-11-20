package org.odk.collect.android.recordwork

import org.javarosa.core.model.Constants
import org.javarosa.core.model.FormDef
import org.javarosa.core.model.GroupDef
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
 * Native schema proof for repeat ROW IDENTITIES, not repeated answer
 * persistence. For now only direct, fixed-type scalar children of a
 * top-level repeat qualify. This separate gate does not grant a JavaRosa
 * editor instance or override the standard ODK upload quarantine.
 */
data class PrivateRepeatGroup(
    val path: String,
    val fields: List<PrivateScalarField>,
    val imagePaths: List<String> = emptyList()
)

data class PrivateRepeatSchema(
    val workId: String,
    val targetXFormSha256: String,
    val groups: List<PrivateRepeatGroup>,
    val canSaveAnswers: Boolean = false,
    val canFinalize: Boolean = false,
    val canUpload: Boolean = false
) {
    init {
        require(!canSaveAnswers && !canFinalize && !canUpload) {
            "A repeat identity inventory is not native repeat form editing"
        }
        require(groups.isNotEmpty() && groups.size <= MAX_GROUPS &&
            groups.map { it.path }.toSet().size == groups.size)
    }

    companion object {
        const val MAX_GROUPS = 3
        const val MAX_FIELDS_PER_GROUP = 12
    }
}

class JavaRosaPrivateRepeatStructureProbe(
    private val scratchRoot: File,
    private val binding: QuarantinedWorkDraft,
    private val plan: HistoricalMappingPlan,
    private val targetXFormXml: String
) {
    val schema: PrivateRepeatSchema

    init {
        require(binding.state == QuarantinedWorkDraft.STATE_QUARANTINED &&
            !binding.canFinalize && !binding.canUpload && !binding.canOpenEditor &&
            binding.workId == plan.workId &&
            binding.sourceSubmissionId == plan.sourceSubmissionId &&
            binding.targetFormVersionId == plan.targetFormVersionId &&
            binding.targetFormId == plan.targetFormId &&
            binding.targetVersion == plan.targetVersion &&
            binding.workType == plan.workType &&
            binding.sourceXFormSha256 == plan.sourceXFormSha256 &&
            binding.targetXFormSha256 == plan.targetXFormSha256 &&
            binding.targetXFormSha256 == JavaRosaPrivateScalarSession.digest(targetXFormXml) &&
            binding.reviewedPaths == plan.candidates.size) {
            "Repeat inventory must match the authorized server work/form identity"
        }
        require(targetXFormXml.length in 1..MAX_XML &&
            !targetXFormXml.contains("<!DOCTYPE", ignoreCase = true) &&
            !targetXFormXml.contains("<!ENTITY", ignoreCase = true) &&
            !EXTERNAL_SOURCE.containsMatchIn(targetXFormXml) &&
            !UNSUPPORTED.containsMatchIn(targetXFormXml)) {
            "External, calculated or dynamic repeat questionnaire is unsupported"
        }
        val reviewed = plan.candidates.filter { MappingRisk.REPEAT in it.risks }
        require(reviewed.isNotEmpty() && reviewed.size <= 30 &&
            reviewed.map { it.targetPath }.toSet().size == reviewed.size &&
            reviewed.all { candidate ->
                MappingRisk.DYNAMIC_SELECT !in candidate.risks &&
                    MappingRisk.CALCULATION !in candidate.risks &&
                    MappingRisk.RELEVANCE_OR_CONSTRAINT !in candidate.risks &&
                    MappingRisk.UNBOUND_FIELD !in candidate.risks &&
                    MappingRisk.STATIC_SELECT !in candidate.risks &&
                    MappingRisk.CHOICE_LIST_CHANGED !in candidate.risks
            }) {
            "Only directly bound plain scalar repeat rows can reserve identities"
        }

        schema = withNativeForm { def ->
            val topLevel = def.children.orEmpty()
            val nativeRepeats = topLevel.filterIsInstance<GroupDef>()
                .filter { it.getRepeat() }
            require(nativeRepeats.size in 1..PrivateRepeatSchema.MAX_GROUPS &&
                nativeRepeats.size == topLevel.count {
                    it is GroupDef && it.getRepeat()
                }) { "Only simple top-level repeat groups are supported" }
            // Any non-repeat group in a native FormDef could conceal a
            // nested repeat or unreviewed question. Refuse such layouts.
            require(topLevel.all { it is GroupDef && it.getRepeat() }) {
                "Repeat identity gate requires flat top-level repeat-only controls"
            }
            val rootName = def.instance.root.name
            nativeRepeats.map { group ->
                val groupRef = group.bind?.reference as? TreeReference
                    ?: throw IllegalArgumentException("Native repeat group is unbound")
                require(groupRef.size() == 2 &&
                    groupRef.getName(0) == rootName &&
                    groupRef.getMultiplicity(0) in setOf(0, TreeReference.INDEX_UNBOUND) &&
                    NAME.matches(groupRef.getName(1))) {
                    "Only one-level absolute repeat paths are supported"
                }
                val path = "/" + rootName + "/" + groupRef.getName(1)
                val children = group.children.orEmpty()
                require(children.isNotEmpty() &&
                    children.size <= PrivateRepeatSchema.MAX_FIELDS_PER_GROUP &&
                    children.all { it is QuestionDef }) {
                    "Nested repeats, groups or empty repeat structures are unsupported"
                }
                // Actual JavaRosa template must exist for each repeat.
                val templateRef = TreeReference.rootRef().apply {
                    add(rootName, 0)
                    add(groupRef.getName(1), TreeReference.INDEX_TEMPLATE)
                }
                val template = def.instance.resolveReference(templateRef)
                    ?: throw IllegalArgumentException("Native repeat template is missing")
                val imagePaths = mutableListOf<String>()
                val fields = children.mapNotNull { raw ->
                    val question = raw as QuestionDef
                    require(question.dynamicChoices == null) {
                        "Dynamic repeat choice fields are not supported"
                    }
                    val fieldRef = question.bind?.reference as? TreeReference
                        ?: throw IllegalArgumentException("Repeat child has no native bind")
                    require(fieldRef.size() == 3 &&
                        fieldRef.getName(0) == rootName &&
                        fieldRef.getName(1) == groupRef.getName(1) &&
                        NAME.matches(fieldRef.getName(2))) {
                        "Only directly bound scalar repeat children may reserve identities"
                    }
                    val fieldPath = path + "/" + fieldRef.getName(2)
                    val candidate = reviewed.singleOrNull { it.targetPath == fieldPath }
                        ?: throw IllegalArgumentException("Repeat child was not explicitly reviewed")
                    require(MappingRisk.REPEAT in candidate.risks)
                    val node = template.getChild(fieldRef.getName(2), 0)
                        ?: throw IllegalArgumentException("JavaRosa repeat template leaf is missing")
                    require(node.isLeaf)
                    if (MappingRisk.MEDIA in candidate.risks) {
                        require(question.controlType == Constants.CONTROL_IMAGE_CHOOSE &&
                            node.dataType == Constants.DATATYPE_BINARY &&
                            imagePaths.size < 2) {
                            "Only two direct native image-upload fields per repeat are allowed"
                        }
                        imagePaths += fieldPath
                        null
                    } else {
                        require(question.controlType == Constants.CONTROL_INPUT) {
                            "Repeat needs unsupported choice, media or calculation widget"
                        }
                        val type = PrivateScalarType.fromNative(node.dataType)
                        require(type in setOf(
                            PrivateScalarType.TEXT, PrivateScalarType.INTEGER,
                            PrivateScalarType.DECIMAL, PrivateScalarType.DATE
                        )) { "Repeat leaf has unsupported native answer type" }
                        PrivateScalarField(fieldPath, requireNotNull(type))
                    }
                }
                require(fields.isNotEmpty() &&
                    fields.size <= PrivateRepeatSchema.MAX_FIELDS_PER_GROUP &&
                    fields.map { it.path }.toSet().size == fields.size &&
                    imagePaths.toSet().size == imagePaths.size &&
                    template.numChildren == children.size &&
                    (0 until template.numChildren).map {
                        template.getChildAt(it).name
                    }.toSet() == children.map {
                        (it as QuestionDef).bind!!.reference.let { ref ->
                            (ref as TreeReference).getName(2)
                        }
                    }.toSet()) {
                    "Repeat has hidden native fields or missing scalar child"
                }
                require(reviewed.filter { it.targetPath.startsWith(path + "/") }
                    .map { it.targetPath }.toSet() ==
                    (fields.map { it.path } + imagePaths).toSet()) {
                    "Repeat contains unreviewed or invisible instance fields"
                }
                PrivateRepeatGroup(path, fields, imagePaths)
            }.also { groups ->
                require(reviewed.map { it.targetPath }.toSet() ==
                    groups.flatMap { g -> g.fields.map { it.path } + g.imagePaths }.toSet()) {
                    "Every reviewed repeat path must belong to one native group"
                }
            }.let { groups ->
                PrivateRepeatSchema(binding.workId, binding.targetXFormSha256, groups)
            }
        }
    }

    private fun <T> withNativeForm(block: (FormDef) -> T): T {
        require(scratchRoot.isDirectory || scratchRoot.mkdirs())
        val folder = File(scratchRoot, "recordwork-repeat-" + UUID.randomUUID())
        require(folder.mkdir()) { "Cannot isolate native repeat schema probe" }
        try {
            val formFile = File(folder, "target.xml")
            formFile.writeText(targetXFormXml, Charsets.UTF_8)
            val noCache = object : FormDefCache {
                override fun readCache(formXml: File?): FormDef? = null
                override fun writeCache(formDef: FormDef?, formPath: String?) = Unit
            }
            val form = requireNotNull(
                FormEntryUseCases.createFormDefFromCacheOrXml(formFile, noCache)
            ) { "JavaRosa rejected repeat target XForm" }
            FormEntryController(FormEntryModel(form))
            form.initialize(true, InstanceInitializationFactory())
            return block(form)
        } finally {
            folder.deleteRecursively()
        }
    }

    companion object {
        private const val MAX_XML = 8_388_608
        private val NAME = Regex("[A-Za-z_][A-Za-z0-9_.-]*")
        private val EXTERNAL_SOURCE = Regex(
            """\bsrc\s*=\s*["']""", RegexOption.IGNORE_CASE
        )
        // Preserve correctness over breadth; richer logic needs a full
        // JavaRosa FormEntryController-backed repeat editor.
        private val UNSUPPORTED = Regex(
            """<\s*(?:[A-Za-z_][\w.-]*:)?(?:itemset|select|select1|setvalue|action|trigger|output)\b|\b(?:calculate|relevant|constraint|readonly|count|noAddRemove)\s*=""",
            RegexOption.IGNORE_CASE
        )
    }
}
