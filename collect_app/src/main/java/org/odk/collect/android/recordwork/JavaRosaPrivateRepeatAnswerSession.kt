package org.odk.collect.android.recordwork

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
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle
import java.util.Date
import java.util.UUID

/**
 * Actually constructs JavaRosa repeat instances for all ACTIVE UUID rows
 * and applies typed answers. The resulting FormDef remains EPHEMERAL and
 * cannot be finalized/exported. Unlike native ODK's repeat ordinal, the
 * UUID ledger is durable; current indices are calculated on every rebuild.
 *
 * No old submission XML is ever imported into the target version.
 */
class JavaRosaPrivateRepeatAnswerSession(
    private val scratchRoot: File,
    private val binding: QuarantinedWorkDraft,
    private val plan: HistoricalMappingPlan,
    private val targetXFormXml: String
) {
    // No caller-supplied schema shortcut. Every editor session derives its
    // authorized repeat inventory from a fresh exact native JavaRosa probe.
    val schema: PrivateRepeatSchema =
        JavaRosaPrivateRepeatStructureProbe(
            scratchRoot, binding, plan, targetXFormXml
        ).schema

    init {
        require(schema.workId == binding.workId &&
            schema.targetXFormSha256 == binding.targetXFormSha256 &&
            binding.targetXFormSha256 == JavaRosaPrivateScalarSession.digest(targetXFormXml) &&
            !schema.canSaveAnswers && !schema.canFinalize && !schema.canUpload &&
            binding.state == QuarantinedWorkDraft.STATE_QUARANTINED &&
            !binding.canFinalize && !binding.canUpload) {
            "Repeat answer session is not authorized to finalize or send"
        }
    }

    /**
     * A full set of active-row values is replayed onto a fresh native target
     * form, so constraints/readback are checked at the correct current
     * multiplicity after row deletion. Archive tombstones never enter it.
     * The caller must supply a verified, unchanged row inventory.
     *
     * Blank strings are permitted only because this is an incomplete draft.
     */
    fun validate(
        inventory: ProtectedRepeatInventory,
        byRow: Map<String, Map<String, String>>
    ): Map<String, Map<String, String>> {
        require(!inventory.canSaveAnswers && !inventory.canFinalize &&
            !inventory.canUpload)
        val active = schema.groups.flatMap { inventory.active(it.path) }
        require(active.size <= 60 && active.map { it.rowId }.toSet().size == active.size &&
            byRow.keys.all { id -> active.any { it.rowId == id } }) {
            "Repeat answers include archived, foreign or ambiguous rows"
        }
        return withNativeTarget { form ->
            val normalized = linkedMapOf<String, Map<String, String>>()
            val root = form.instance.root.name
            for (group in schema.groups) {
                val groupPieces = group.path.removePrefix("/").split("/")
                require(groupPieces.size == 2 && groupPieces.first() == root) {
                    "Repeat root does not match the authorized XForm"
                }
                val name = groupPieces.last()
                val templateRef = TreeReference.rootRef().apply {
                    add(root, 0)
                    add(name, TreeReference.INDEX_TEMPLATE)
                }
                val template = requireNotNull(form.instance.resolveReference(templateRef)) {
                    "Native repeat template missing"
                }
                // JavaRosa's blank-form initialization may create one
                // *empty* repeat instance at ordinal zero. Reuse it only
                // after verifying it matches every reviewed typed child and
                // contains NO answers, defaults or extra hidden values.
                // Never merge data from a prepopulated source instance.
                val initialized = form.instance.root.getChildrenWithName(name)
                    .orEmpty().filter { it.mult != TreeReference.INDEX_TEMPLATE }
                require(initialized.size <= 1 &&
                    (initialized.isEmpty() || initialized.single().mult == 0)) {
                    "Unexpected pre-populated native repeat instances"
                }
                initialized.singleOrNull()?.let { blank ->
                    require(blank.numChildren ==
                        group.fields.size + group.imagePaths.size) {
                        "Blank native repeat contains unreviewed fields"
                    }
                    val expected = (group.fields.map {
                        it.path.substringAfterLast("/")
                    } + group.imagePaths.map {
                        it.substringAfterLast("/")
                    }).toSet()
                    require(
                        (0 until blank.numChildren).map {
                            blank.getChildAt(it).name
                        }.toSet() == expected
                    ) { "Native repeat contains unknown leaves" }
                    for (field in group.fields) {
                        val node = requireNotNull(
                            blank.getChild(field.path.substringAfterLast("/"), 0)
                        ) { "Initial JavaRosa repeat is missing a reviewed leaf" }
                        require(node.isLeaf &&
                            node.dataType == field.type.javaRosaType &&
                            node.value == null) {
                            "Native repeat has existing data or changed field type"
                        }
                    }
                    for (path in group.imagePaths) {
                        val node = requireNotNull(
                            blank.getChild(path.substringAfterLast("/"), 0)
                        ) { "Initial JavaRosa repeat missing native image field" }
                        require(node.isLeaf &&
                            node.dataType == org.javarosa.core.model.Constants.DATATYPE_BINARY &&
                            node.value == null) {
                            "Native repeat image has unexpected existing data"
                        }
                    }
                }

                for ((index, row) in inventory.active(group.path).withIndex()) {
                    val target = TreeReference.rootRef().apply {
                        add(root, 0)
                        add(name, index)
                    }
                    val newNode = if (index == 0 && initialized.size == 1) {
                        initialized.single()
                    } else {
                        form.instance.copyNode(template, target)
                    }
                    require(newNode.mult == index &&
                        form.instance.resolveReference(target) != null) {
                        "Failed to create a unique native repeat instance"
                    }
                    val supplied = byRow[row.rowId].orEmpty()
                    require(supplied.keys.all { path -> group.fields.any { it.path == path } } &&
                        supplied.size <= group.fields.size) {
                        "Answer path is not a reviewed leaf in this row"
                    }
                    val clean = linkedMapOf<String, String>()
                    for (field in group.fields) {
                        val value = supplied[field.path].orEmpty()
                        require(value.length <= MAX_TEXT &&
                            '\u0000' !in value) {
                            "Repeat answer exceeds allowed length"
                        }
                        val leaf = TreeReference.rootRef().apply {
                            add(root, 0)
                            add(name, index)
                            add(field.path.substringAfterLast("/"), 0)
                        }
                        val node = requireNotNull(form.instance.resolveReference(leaf)) {
                            "New JavaRosa repeat leaf is missing"
                        }
                        require(node.isLeaf && node.dataType == field.type.javaRosaType) {
                            "Repeat target leaf type changed"
                        }
                        if (value.isEmpty()) {
                            clean[field.path] = ""
                            continue
                        }
                        val (answer, canonical) = parse(field.type, value)
                        require(form.evaluateConstraint(leaf, answer)) {
                            "JavaRosa rejected a repeated answer"
                        }
                        form.setValue(answer, leaf, true)
                        val readback = form.instance.resolveReference(leaf)?.value?.value
                            ?: throw IllegalArgumentException(
                                "JavaRosa did not retain a repeated answer"
                            )
                        require(matches(field.type, answer.value, readback)) {
                            "Native repeated answer differs after write"
                        }
                        clean[field.path] = canonical
                    }
                    normalized[row.rowId] = clean.toMap()
                }
            }
            normalized.toMap()
        }
    }

    private fun parse(type: PrivateScalarType, raw: String): Pair<IAnswerData, String> =
        when (type) {
            PrivateScalarType.TEXT -> StringData(raw) to raw
            PrivateScalarType.INTEGER -> {
                require(INTEGER.matches(raw) && raw.length <= 11) {
                    "Repeat integer must be canonical 32-bit syntax"
                }
                val value = requireNotNull(raw.toIntOrNull()) {
                    "Repeat integer exceeds native JavaRosa range"
                }
                IntegerData(value) to value.toString()
            }
            PrivateScalarType.DECIMAL -> {
                require(DECIMAL.matches(raw) && raw.length <= 36) {
                    "Repeat decimal contains invalid format"
                }
                val precise = BigDecimal(raw).stripTrailingZeros()
                require(precise.precision() <= 15 &&
                    precise.abs() <= MAX_DECIMAL) {
                    "Repeat decimal exceeds supported precision or magnitude"
                }
                val value = precise.toDouble()
                require(value.isFinite())
                DecimalData(value) to (
                    if (precise.compareTo(BigDecimal.ZERO) == 0) "0"
                    else precise.toPlainString()
                )
            }
            PrivateScalarType.DATE -> {
                require(DATE.matches(raw)) { "Date must be YYYY-MM-DD" }
                val day = LocalDate.parse(raw, ISO_DATE)
                require(day.year in 1900..2100) {
                    "Repeat date is outside supported calendar range"
                }
                val native = Date.from(
                    day.atStartOfDay(ZoneId.systemDefault()).toInstant()
                )
                DateData(native) to day.toString()
            }
            else -> throw IllegalArgumentException(
                "Select/media answers in repeats are not supported"
            )
        }

    private fun matches(type: PrivateScalarType, expected: Any, actual: Any): Boolean =
        if (type == PrivateScalarType.DATE) {
            val expectedDay = (expected as? Date)?.toInstant()
                ?.atZone(ZoneId.systemDefault())?.toLocalDate()
            val actualDay = (actual as? Date)?.toInstant()
                ?.atZone(ZoneId.systemDefault())?.toLocalDate()
            expectedDay != null && expectedDay == actualDay
        } else {
            expected == actual
        }

    private fun <T> withNativeTarget(block: (FormDef) -> T): T {
        require(scratchRoot.isDirectory || scratchRoot.mkdirs())
        val folder = File(scratchRoot, "recordwork-repeat-answers-" + UUID.randomUUID())
        require(folder.mkdir()) { "Cannot isolate native repeat answer workspace" }
        try {
            val xform = File(folder, "target.xml")
            xform.writeText(targetXFormXml, Charsets.UTF_8)
            val noCache = object : FormDefCache {
                override fun readCache(formXml: File?): FormDef? = null
                override fun writeCache(formDef: FormDef?, formPath: String?) = Unit
            }
            val form = requireNotNull(
                FormEntryUseCases.createFormDefFromCacheOrXml(xform, noCache)
            ) { "JavaRosa rejected reviewed target repeat XForm" }
            FormEntryController(FormEntryModel(form))
            form.initialize(true, InstanceInitializationFactory())
            return block(form)
        } finally {
            folder.deleteRecursively()
        }
    }

    companion object {
        const val MAX_TEXT = 16_384
        private val INTEGER = Regex("-?(0|[1-9][0-9]*)")
        private val DECIMAL = Regex("-?(0|[1-9][0-9]*)(\\.[0-9]+)?")
        private val DATE = Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}")
        private val MAX_DECIMAL = BigDecimal("1000000000000")
        private val ISO_DATE = DateTimeFormatter.ISO_LOCAL_DATE
            .withResolverStyle(ResolverStyle.STRICT)
    }
}
