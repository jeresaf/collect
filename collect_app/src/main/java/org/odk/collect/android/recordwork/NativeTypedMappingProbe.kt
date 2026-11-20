package org.odk.collect.android.recordwork

import org.javarosa.core.model.FormDef
import org.javarosa.core.model.IFormElement
import org.javarosa.core.model.QuestionDef
import org.javarosa.core.model.Constants
import org.javarosa.core.model.instance.InstanceInitializationFactory
import org.javarosa.core.model.instance.TreeReference
import org.javarosa.form.api.FormEntryController
import org.javarosa.form.api.FormEntryModel
import org.odk.collect.android.formentry.FormDefCache
import org.odk.collect.android.formentry.FormEntryUseCases
import java.io.File
import java.util.UUID

/**
 * M4C-2B-3C-3B-1: exact-version, native JavaRosa source/target advisory probe.
 * No old submission data or previous answer values enter this class.
 * Its findings are NOT mapping approvals and cannot be used for import/send.
 */
class NativeTypedMappingProbe(private val privateCacheDirectory: File) {
    fun inspect(
        plan: HistoricalMappingPlan,
        sourceXFormXml: String?,
        targetXFormXml: String
    ): List<TypedMappingFinding> {
        require(plan.workType == RecordWorkType.AMENDMENT) {
            "Independent follow-up visits never import historical answers"
        }
        require(!sourceXFormXml.isNullOrBlank() &&
            plan.sourceXFormSha256 == ReviewedScalarTransferProbe.sha256(sourceXFormXml) &&
            plan.targetXFormSha256 == ReviewedScalarTransferProbe.sha256(targetXFormXml)) {
            "Exact historical/target version fingerprints required"
        }
        require(plan.sourceFormId == plan.targetFormId &&
            plan.globalRisks.none { it in setOf(
                MappingRisk.NO_HISTORICAL_SCHEMA,
                MappingRisk.SOURCE_SCHEMA_MISMATCH,
                MappingRisk.DIFFERENT_FORM_FOLLOW_UP
            ) }) { "Historical form identity mismatch" }
        require(plan.candidates.isNotEmpty() &&
            plan.candidates.size <= MAX_FIELDS &&
            plan.candidates.map { it.targetPath }.toSet().size == plan.candidates.size) {
            "Invalid target mapping inventory"
        }
        require(privateCacheDirectory.isDirectory || privateCacheDirectory.mkdirs()) {
            "Private native schema workspace unavailable"
        }
        val scratch = File(privateCacheDirectory, "recordwork-typed-" + UUID.randomUUID())
        require(scratch.mkdir()) { "Cannot allocate native schema workspace" }
        try {
            val sourceFields = inspectForm(File(scratch, "source.xml"),
                requireNotNull(sourceXFormXml))
            val targetFields = inspectForm(File(scratch, "target.xml"), targetXFormXml)
            require(targetFields.map { it.path }.toSet() ==
                plan.candidates.map { it.targetPath }.toSet()) {
                "Native target question inventory differs from reviewed schema"
            }
            require(sourceFields.map { it.path }.toSet() ==
                (plan.candidates.mapNotNull { it.sourcePath } + plan.removedSourcePaths).toSet()) {
                "Native historical question inventory differs from reviewed schema"
            }
            val risks = plan.candidates.filter { candidate ->
                candidate.risks.any { it !in setOf(
                    MappingRisk.EXPLICIT_REVIEW_REQUIRED, MappingRisk.STATIC_SELECT,
                    MappingRisk.CHOICE_LIST_CHANGED, MappingRisk.TYPE_CHANGED,
                    MappingRisk.MISSING_SOURCE_FIELD
                ) }
            }.map { it.targetPath }.toSet()
            return ReviewedTypedMappingClassifier.inspect(
                plan.workType, sourceFields, targetFields, risks
            ).map { finding ->
                val candidate = plan.candidates.single { it.targetPath == finding.targetPath }
                // Historical XML/schema assessment takes precedence over a
                // superficial matching native type (e.g. changed choice list,
                // calculated, relevance, required, source missing).
                val blocked = when {
                    MappingRisk.MISSING_SOURCE_FIELD in candidate.risks ->
                        TypedMappingVerdict.BLOCKED_MISSING_SOURCE
                    MappingRisk.TYPE_CHANGED in candidate.risks ->
                        TypedMappingVerdict.BLOCKED_TYPE_CHANGE
                    MappingRisk.CHOICE_LIST_CHANGED in candidate.risks ->
                        TypedMappingVerdict.BLOCKED_CHOICE_CHANGE
                    else -> null
                }
                if (blocked != null && finding.verdict == TypedMappingVerdict.REVIEW_SAME_TYPE)
                    finding.copy(verdict = blocked,
                        reason = "Reviewed historical schema reports a compatibility change")
                else finding
            }
        } finally {
            scratch.deleteRecursively()
        }
    }

    private fun inspectForm(file: File, xml: String): List<PrivateScalarField> {
        require(xml.isNotBlank() && xml.length <= 8_388_608 &&
            !xml.contains("<!DOCTYPE", ignoreCase = true) &&
            !xml.contains("<!ENTITY", ignoreCase = true) &&
            !UNSAFE_SOURCE.containsMatchIn(xml) &&
            !UNSAFE_BIND.containsMatchIn(xml)) {
            "Complex or externally dependent forms require independent qualification"
        }
        file.writeText(xml, Charsets.UTF_8)
        val noCache = object : FormDefCache {
            override fun readCache(formXml: File?): FormDef? = null
            override fun writeCache(formDef: FormDef?, formPath: String?) = Unit
        }
        val def = FormEntryUseCases.createFormDefFromCacheOrXml(file, noCache)
            ?: throw IllegalArgumentException("Native JavaRosa rejected XForm")
        FormEntryController(FormEntryModel(def))
        def.initialize(true, InstanceInitializationFactory())
        val fields = linkedMapOf<String, PrivateScalarField>()
        fun visit(elements: List<IFormElement>?) {
            elements.orEmpty().forEach { element ->
                if (element is QuestionDef) {
                    val reference = element.bind?.reference as? TreeReference
                        ?: throw IllegalArgumentException("Question lacks absolute native bind")
                    require(reference.size() in 2..12 &&
                        (0 until reference.size()).all {
                            reference.getMultiplicity(it) == 0 ||
                                reference.getMultiplicity(it) == TreeReference.INDEX_UNBOUND
                        }) { "Repeat question requires separate reviewed migration" }
                    val path = "/" + (0 until reference.size())
                        .joinToString("/") { reference.getName(it) }
                    require(path.matches(SAFE_PATH) && path !in fields) {
                        "Unsafe/duplicate native field path"
                    }
                    val leaf = def.instance.resolveReference(reference)
                        ?: throw IllegalArgumentException("Question is absent from native model")
                    require(leaf.isLeaf) { "Native question must be a scalar leaf" }
                    val type = PrivateScalarType.fromNative(leaf.dataType)
                        ?: throw IllegalArgumentException("Unsupported native answer type")
                    val choices = if (type == PrivateScalarType.SELECT_ONE ||
                        type == PrivateScalarType.SELECT_MULTI) {
                        val control = if (type == PrivateScalarType.SELECT_ONE)
                            Constants.CONTROL_SELECT_ONE else Constants.CONTROL_SELECT_MULTI
                        require(element.controlType == control &&
                            element.dynamicChoices == null) {
                            "Dynamic or mismatched native select is not eligible"
                        }
                        element.choices.orEmpty().map {
                            PrivateStaticChoice(it.value,
                                it.labelInnerText?.takeIf(String::isNotBlank) ?: it.value)
                        }.also { values ->
                            require(values.isNotEmpty() && values.size <= 40 &&
                                values.map { it.value }.toSet().size == values.size) {
                                "Ambiguous or oversized native static choices"
                            }
                        }
                    } else {
                        require(element.controlType != Constants.CONTROL_SELECT_ONE &&
                            element.controlType != Constants.CONTROL_SELECT_MULTI) {
                            "Native control and answer type disagree"
                        }
                        emptyList()
                    }
                    fields[path] = PrivateScalarField(path, type, choices)
                } else visit(element.children)
            }
        }
        visit(def.children)
        require(fields.isNotEmpty() && fields.size <= MAX_FIELDS) {
            "No eligible or oversized native question inventory"
        }
        return fields.values.toList()
    }

    companion object {
        private const val MAX_FIELDS = 30
        private val SAFE_PATH = Regex(
            "/[A-Za-z_][A-Za-z0-9_.-]*(/[A-Za-z_][A-Za-z0-9_.-]*){1,11}"
        )
        private val UNSAFE_SOURCE = Regex(
            """\bsrc\s*=\s*["']""", RegexOption.IGNORE_CASE
        )
        private val UNSAFE_BIND = Regex(
            """\b(?:calculate|relevant|constraint|required|readonly)\s*=""",
            RegexOption.IGNORE_CASE
        )
    }
}
