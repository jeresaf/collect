package org.odk.collect.android.recordwork

import org.javarosa.core.model.Constants
import org.javarosa.core.model.FormDef
import org.javarosa.core.model.data.DateData
import org.javarosa.core.model.data.DecimalData
import org.javarosa.core.model.data.IAnswerData
import org.javarosa.core.model.data.IntegerData
import org.javarosa.core.model.data.StringData
import org.javarosa.core.model.instance.InstanceInitializationFactory
import org.javarosa.core.model.instance.TreeReference
import org.javarosa.form.api.FormEntryController
import org.javarosa.form.api.FormEntryModel
import org.odk.collect.android.formentry.FormDefCache
import org.odk.collect.android.formentry.FormEntryUseCases
import java.io.File
import java.math.BigDecimal
import java.security.MessageDigest
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle
import java.util.Date
import java.util.UUID

/**
 * A bounded manually-entered subset of native JavaRosa answer types.
 * This is NOT the full ODK native form UI, nor an instance exporter.
 */
enum class PrivateScalarType(val javaRosaType: Int) {
    TEXT(Constants.DATATYPE_TEXT),
    INTEGER(Constants.DATATYPE_INTEGER),
    DECIMAL(Constants.DATATYPE_DECIMAL),
    DATE(Constants.DATATYPE_DATE);

    companion object {
        fun fromNative(type: Int): PrivateScalarType? =
            entries.firstOrNull { it.javaRosaType == type }
    }
}

data class PrivateScalarField(val path: String, val type: PrivateScalarType)

/**
 * Creates a fresh exact target-version FormDef on each validation. Only the
 * canonical scalar strings are returned for the existing AES-GCM draft store.
 *
 * The caller must separately reauthorize the READY work online on open AND
 * save and read its unchanged binding from the no-backup vault.
 */
class JavaRosaPrivateScalarSession(
    private val scratchRoot: File,
    val binding: QuarantinedWorkDraft,
    val plan: HistoricalMappingPlan,
    private val targetXFormXml: String
) {
    private val rootName: String
    val questions: List<PrivateScalarField>
    val fields: List<String> get() = questions.map { it.path }

    init {
        require(binding.state == QuarantinedWorkDraft.STATE_QUARANTINED &&
            !binding.canOpenEditor && !binding.canFinalize && !binding.canUpload) {
            "Target form validation grants no ODK draft or send authority"
        }
        require(binding.workId == plan.workId &&
            binding.sourceSubmissionId == plan.sourceSubmissionId &&
            binding.targetFormVersionId == plan.targetFormVersionId &&
            binding.targetFormId == plan.targetFormId &&
            binding.targetVersion == plan.targetVersion &&
            binding.workType == plan.workType &&
            binding.sourceXFormSha256 == plan.sourceXFormSha256 &&
            binding.targetXFormSha256 == plan.targetXFormSha256 &&
            binding.targetXFormSha256 == digest(targetXFormXml) &&
            binding.reviewedPaths == plan.candidates.size) {
            "Current server form/work differs from bound private draft"
        }
        require(plan.candidates.isNotEmpty() &&
            plan.candidates.size <= MAX_FIELDS &&
            plan.candidates.map { it.targetPath }.toSet().size ==
                plan.candidates.size) {
            "Unreviewed or oversized question inventory"
        }
        rootName = plan.candidates.first().targetPath
            .removePrefix("/").substringBefore("/")
        require(SAFE_NAME.matches(rootName))
        require(plan.candidates.all { candidate ->
            candidate.targetPath.startsWith("/" + rootName + "/") &&
                candidate.targetPath.removePrefix("/").split("/")
                    .all(SAFE_NAME::matches) &&
                candidate.risks.intersect(UNSUPPORTED).isEmpty()
        }) {
            "This questionnaire requires full ODK widgets, not scalar editing"
        }
        require(targetXFormXml.isNotBlank() &&
            targetXFormXml.length <= MAX_FORM_XML &&
            !targetXFormXml.contains("<!DOCTYPE", ignoreCase = true) &&
            !targetXFormXml.contains("<!ENTITY", ignoreCase = true) &&
            !EXTERNAL_SRC.containsMatchIn(targetXFormXml) &&
            !UNSAFE_CONTROL.containsMatchIn(targetXFormXml) &&
            !UNSAFE_BIND_ATTR.containsMatchIn(targetXFormXml)) {
            "Unsupported control, conditional rule or external XForm resource"
        }

        questions = withNativeForm { def ->
            plan.candidates.map { candidate ->
                val path = candidate.targetPath
                val node = def.instance.resolveReference(ref(path))
                    ?: throw IllegalArgumentException("JavaRosa target question is missing")
                require(node.isLeaf) { "Only single-value target fields are supported" }
                val scalarType = requireNotNull(
                    PrivateScalarType.fromNative(node.dataType)
                ) { "Unsupported JavaRosa target answer type" }
                PrivateScalarField(path, scalarType)
            }
        }
    }

    /**
     * Typed native answers are applied to a fresh target FormDef, validated
     * through JavaRosa, checked by readback, and immediately discarded.
     * Empty fields are allowed for an incomplete DRAFT, never submission.
     * Nothing is copied from the historical source instance.
     */
    fun validateForPrivateSave(
        rawAnswers: Map<String, String>
    ): Map<String, String> {
        require(rawAnswers.keys.all { it in fields } &&
            rawAnswers.size <= questions.size &&
            rawAnswers.values.all {
                it.length <= MAX_TEXT && '\u0000' !in it
            }) {
            "Unreviewed or oversized answer"
        }
        return withNativeForm { def ->
            val validated = linkedMapOf<String, String>()
            for (question in questions) {
                val raw = rawAnswers[question.path].orEmpty()
                val reference = ref(question.path)
                val element = requireNotNull(
                    def.instance.resolveReference(reference)
                ) { "Original target question is missing" }
                require(element.isLeaf &&
                    element.dataType == question.type.javaRosaType) {
                    "Target native data type changed after review"
                }
                if (raw.isEmpty()) {
                    validated[question.path] = ""
                    continue
                }
                val (answer, canonical) = parse(question.type, raw)
                require(def.evaluateConstraint(reference, answer)) {
                    "Selected target version rejected the answer"
                }
                def.setValue(answer, reference, true)
                val readback = def.instance.resolveReference(reference)
                    ?.value?.value ?: throw IllegalArgumentException(
                        "JavaRosa did not retain the typed answer"
                    )
                require(nativeMatches(question.type, answer.value, readback)) {
                    "JavaRosa modified the entered value"
                }
                validated[question.path] = canonical
            }
            validated.toMap()
        }
    }

    private fun parse(type: PrivateScalarType, raw: String): Pair<IAnswerData, String> =
        when (type) {
            PrivateScalarType.TEXT -> StringData(raw) to raw
            PrivateScalarType.INTEGER -> {
                require(INTEGER_PATTERN.matches(raw) && raw.length <= 11) {
                    "Integer answer is not an unambiguous 32-bit value"
                }
                val n = requireNotNull(raw.toIntOrNull()) {
                    "Integer exceeds JavaRosa 32-bit range"
                }
                IntegerData(n) to n.toString()
            }
            PrivateScalarType.DECIMAL -> {
                require(DECIMAL_PATTERN.matches(raw) && raw.length <= 36) {
                    "Decimal must use a period and have no exponent"
                }
                val exact = BigDecimal(raw).stripTrailingZeros()
                require(exact.precision() <= 15 &&
                    exact.abs() <= MAX_DECIMAL_MAGNITUDE) {
                    "Decimal precision or magnitude exceeds safe JavaRosa bounds"
                }
                val value = exact.toDouble()
                require(value.isFinite()) { "Invalid JavaRosa double" }
                DecimalData(value) to (
                    if (exact.compareTo(BigDecimal.ZERO) == 0) "0"
                    else exact.toPlainString()
                )
            }
            PrivateScalarType.DATE -> {
                require(DATE_PATTERN.matches(raw)) {
                    "Date must be ISO YYYY-MM-DD"
                }
                val day = LocalDate.parse(raw, ISO_DATE)
                require(day.year in 1900..2100) { "Date outside supported range" }
                val date = Date.from(
                    day.atStartOfDay(ZoneId.systemDefault()).toInstant()
                )
                DateData(date) to day.toString()
            }
        }

    private fun nativeMatches(type: PrivateScalarType, expected: Any, actual: Any): Boolean =
        when (type) {
            PrivateScalarType.DATE -> {
                val a = (actual as? Date)?.toInstant()
                    ?.atZone(ZoneId.systemDefault())?.toLocalDate()
                val e = (expected as? Date)?.toInstant()
                    ?.atZone(ZoneId.systemDefault())?.toLocalDate()
                a != null && a == e
            }
            else -> expected == actual
        }

    private fun <T> withNativeForm(block: (FormDef) -> T): T {
        require(scratchRoot.isDirectory || scratchRoot.mkdirs()) {
            "Private form scratch directory unavailable"
        }
        val folder = File(scratchRoot, "recordwork-scalar-" + UUID.randomUUID())
        require(folder.mkdir()) { "Cannot create isolated JavaRosa form workspace" }
        try {
            val formFile = File(folder, "target.xml")
            formFile.writeText(targetXFormXml, Charsets.UTF_8)
            val noCache = object : FormDefCache {
                override fun readCache(formXml: File?): FormDef? = null
                override fun writeCache(formDef: FormDef?, formPath: String?) = Unit
            }
            val def = FormEntryUseCases.createFormDefFromCacheOrXml(formFile, noCache)
                ?: throw IllegalArgumentException("Native JavaRosa rejected target XForm")
            FormEntryController(FormEntryModel(def))
            def.initialize(true, InstanceInitializationFactory())
            require(def.instance.root.name == rootName) {
                "Native model root does not match reviewed target fields"
            }
            return block(def)
        } finally {
            folder.deleteRecursively()
        }
    }

    private fun ref(path: String): TreeReference {
        val parts = path.removePrefix("/").split("/")
        require(parts.size in 2..12 && parts.first() == rootName &&
            parts.all(SAFE_NAME::matches)) { "Invalid target JavaRosa path" }
        return TreeReference.rootRef().apply {
            parts.forEach { add(it, 0) }
        }
    }

    companion object {
        const val MAX_FIELDS = 30
        const val MAX_TEXT = 16_384
        private const val MAX_FORM_XML = 8_388_608
        private val MAX_DECIMAL_MAGNITUDE = BigDecimal("1000000000000")
        private val INTEGER_PATTERN = Regex("-?(0|[1-9][0-9]*)")
        private val DECIMAL_PATTERN = Regex("-?(0|[1-9][0-9]*)(\\.[0-9]+)?")
        private val DATE_PATTERN = Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}")
        private val ISO_DATE = DateTimeFormatter.ISO_LOCAL_DATE
            .withResolverStyle(ResolverStyle.STRICT)
        private val SAFE_NAME = Regex("[A-Za-z_][A-Za-z0-9_.-]*")
        private val EXTERNAL_SRC =
            Regex("""\bsrc\s*=\s*["']""", RegexOption.IGNORE_CASE)
        private val UNSAFE_CONTROL = Regex(
            """<\s*(?:[A-Za-z_][\w.-]*:)?(?:repeat|select|select1|upload|itemset|trigger|setvalue|setgeopoint|action|output|rank|range|secret|barcode|geopoint|geotrace|geoshape)\b""",
            RegexOption.IGNORE_CASE
        )
        private val UNSAFE_BIND_ATTR = Regex(
            """\b(?:relevant|constraint|calculate|readonly)\s*=""",
            RegexOption.IGNORE_CASE
        )
        private val UNSUPPORTED = setOf(
            MappingRisk.REPEAT, MappingRisk.MEDIA, MappingRisk.STATIC_SELECT,
            MappingRisk.DYNAMIC_SELECT, MappingRisk.CHOICE_LIST_CHANGED,
            MappingRisk.CALCULATION, MappingRisk.RELEVANCE_OR_CONSTRAINT,
            MappingRisk.UNBOUND_FIELD
        )

        fun digest(s: String): String = MessageDigest.getInstance("SHA-256")
            .digest(s.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}
