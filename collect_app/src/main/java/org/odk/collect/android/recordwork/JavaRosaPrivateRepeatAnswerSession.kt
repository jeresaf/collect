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
    private val targetXFormXml: String,
    allowMixedDirect: Boolean = false
) {
    // No caller-supplied schema shortcut. Every editor session derives its
    // authorized repeat inventory from a fresh exact native JavaRosa probe.
    val schema: PrivateRepeatSchema =
        JavaRosaPrivateRepeatStructureProbe(
            scratchRoot, binding, plan, targetXFormXml, allowMixedDirect
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

    /**
     * RAM-only binary reference verification on the exact native repeat
     * multiplicity. The caller must hold the inventory lock and provide
     * receipts authenticated by the UUID-scoped encrypted image store.
     * Never persist or expose the logical native filenames.
     */
    internal fun verifyImageReferences(
        inventory: ProtectedRepeatInventory,
        receipts: Map<Pair<String, String>, ProtectedRepeatImageReceipt>,
        savedAnswers: Map<String, Map<String, String>> = emptyMap(),
        directAnswers: Map<String, String> = emptyMap(),
        directReceipts: Map<String, ProtectedImageReceipt> = emptyMap(),
        requireComplete: Boolean = false,
        onSerialized: ((ProtectedNativeXmlEvidence, ByteArray) -> Unit)? = null,
        useAttachmentNames: Boolean = false
    ): Map<Pair<String, String>, Int> = withNativeTarget { form ->
        require(onSerialized == null || requireComplete)
        require(!useAttachmentNames || onSerialized != null)
        require(schema.groups.size == 1)
        val group = schema.groups.single()
        val active = inventory.active(group.path)
        require(active.isNotEmpty() && group.imagePaths.isNotEmpty())
        require(receipts.keys.all { (id, path) ->
            active.any { it.rowId == id } && path in group.imagePaths
        })
        require(savedAnswers.keys.all { id -> active.any { it.rowId == id } } &&
            savedAnswers.values.all { it.keys == group.fields.map { f -> f.path }.toSet() })
        if (requireComplete) {
            // Deliberately stricter than generic XForms required semantics:
            // all reviewed leaves in this bounded flat profile must be filled.
            require(schema.directTextPaths.isNotEmpty() && schema.directImagePaths.isNotEmpty())
            require(directAnswers.keys == schema.directTextPaths.toSet() &&
                directAnswers.values.all { it.isNotBlank() } &&
                directReceipts.keys == schema.directImagePaths.toSet())
            require(savedAnswers.keys == active.map { it.rowId }.toSet() &&
                savedAnswers.values.all { answers -> answers.values.all { it.isNotBlank() } })
            require(receipts.keys == active.flatMap { row ->
                group.imagePaths.map { row.rowId to it }
            }.toSet())
        }
        val root = form.instance.root.name
        val name = group.path.substringAfterLast('/')
        require(group.path == "/$root/$name")
        val templateRef = TreeReference.rootRef().apply {
            add(root, 0)
            add(name, TreeReference.INDEX_TEMPLATE)
        }
        val template = requireNotNull(form.instance.resolveReference(templateRef))
        val initial = form.instance.root.getChildrenWithName(name).orEmpty()
            .filter { it.mult != TreeReference.INDEX_TEMPLATE }
        require(initial.size <= 1 && initial.all { it.mult == 0 })
        val references = linkedMapOf<Pair<String, String>, Pair<TreeReference, String?>>()
        val ordinals = linkedMapOf<Pair<String, String>, Int>()
        val scalars = mutableListOf<Triple<TreeReference, PrivateScalarType, IAnswerData?>>()
        scalars.addAll(applyDirectText(form, directAnswers))
        require(directReceipts.keys.all { it in schema.directImagePaths })
        for (path in schema.directImagePaths) {
            val leaf = directRef(form, path)
            val field = requireNotNull(form.instance.resolveReference(leaf))
            require(field.isLeaf && field.value == null &&
                field.dataType == org.javarosa.core.model.Constants.DATATYPE_BINARY)
            val receipt = directReceipts[path]
            val token = receipt?.let {
                require(it.fieldPath == path && !it.canFinalize && !it.canSubmit)
                val opaque = "protected-" + QuarantinedWorkDraftVault.sha256(
                    binding.reservedInstanceId + "\n" + path + "\n" + it.sha256)
                if (useAttachmentNames) ProtectedNativePackageBuilder.filename(
                    binding, "direct" to path, it.mime, it.sha256) else opaque
            }
            if (token != null) form.setValue(StringData(token), leaf, true)
            references["direct" to path] = leaf to token
        }
        fun assertScalars() {
            for ((leaf, type, answer) in scalars) {
                val actual = form.instance.resolveReference(leaf)?.value?.value
                require(if (answer == null) actual == null
                    else actual != null && matches(type, answer.value, actual)) {
                    "Recovered scalar answer changed in combined native model"
                }
            }
        }
        for ((ordinal, row) in active.withIndex()) {
            val rowRef = TreeReference.rootRef().apply {
                add(root, 0)
                add(name, ordinal)
            }
            val node = if (ordinal == 0 && initial.size == 1) initial.single()
                else form.instance.copyNode(template, rowRef)
            require(node.mult == ordinal && node.numChildren ==
                group.fields.size + group.imagePaths.size)
            require((0 until node.numChildren).all { node.getChildAt(it).value == null })
            for (field in group.fields) {
                val leaf = TreeReference.rootRef().apply {
                    add(root, 0)
                    add(name, ordinal)
                    add(field.path.substringAfterLast('/'), 0)
                }
                val native = requireNotNull(form.instance.resolveReference(leaf))
                require(native.isLeaf && native.dataType == field.type.javaRosaType &&
                    native.value == null)
                val raw = savedAnswers[row.rowId]?.get(field.path).orEmpty()
                require(raw.length <= MAX_TEXT && '\u0000' !in raw)
                val answer = if (raw.isEmpty()) null else parse(field.type, raw).let {
                    require(it.second == raw && form.evaluateConstraint(leaf, it.first))
                    it.first
                }
                if (answer != null) form.setValue(answer, leaf, true)
                scalars += Triple(leaf, field.type, answer)
            }
            for (path in group.imagePaths) {
                val key = row.rowId to path
                val leaf = TreeReference.rootRef().apply {
                    add(root, 0)
                    add(name, ordinal)
                    add(path.substringAfterLast('/'), 0)
                }
                val field = requireNotNull(form.instance.resolveReference(leaf))
                require(field.isLeaf &&
                    field.dataType == org.javarosa.core.model.Constants.DATATYPE_BINARY &&
                    field.value == null)
                val receipt = receipts[key]
                val token = receipt?.let {
                    require(it.rowId == row.rowId && it.fieldPath == path &&
                        !it.canFinalize && !it.canUpload)
                    val opaque = "protected-" + QuarantinedWorkDraftVault.sha256(
                        binding.reservedInstanceId + "\n" + row.rowId + "\n" +
                            path + "\n" + it.sha256
                    )
                    if (useAttachmentNames) ProtectedNativePackageBuilder.filename(
                        binding, key, it.mime, it.sha256) else opaque
                }
                if (token != null) {
                    form.setValue(StringData(token), leaf, true)
                    ordinals[key] = ordinal
                }
                references[key] = leaf to token
            }
        }
        require(form.instance.root.getChildrenWithName(name).orEmpty()
            .count { it.mult != TreeReference.INDEX_TEMPLATE } == active.size)
        require(references.values.mapNotNull { it.second }.toSet().size ==
            receipts.size + directReceipts.size)
        // Read all rows after all writes, not just immediately after a write.
        for ((leaf, token) in references.values) {
            require(form.instance.resolveReference(leaf)?.value?.value == token)
        }
        assertScalars()
        if (requireComplete) {
            // Exact root/metadata inventory; amendments are independently gated.
            require(ProtectedReentryWork.eligible(binding))
            val rootNode = form.instance.root
            val expectedRootNames = (schema.directTextPaths + schema.directImagePaths)
                .map { it.substringAfterLast('/') }.toSet() + setOf(name, "meta")
            require((0 until rootNode.numChildren).map { rootNode.getChildAt(it).name }.toSet() ==
                expectedRootNames)
            val meta = requireNotNull(rootNode.getChild("meta", 0))
            val expectedMetadata = ProtectedReentryWork.metadata(binding)
            val existingMetadata = (0 until meta.numChildren).map { meta.getChildAt(it).name }
            require(existingMetadata.size == existingMetadata.toSet().size &&
                "instanceID" in existingMetadata && existingMetadata.all { it in expectedMetadata })
            if (binding.requiresDeprecatedId) {
                // Add only the protocol-owned revision identity to the ephemeral
                // native model. No historical business value is imported.
                val deprecated = meta.getChild("deprecatedID", 0) ?:
                    org.javarosa.core.model.instance.TreeElement("deprecatedID", 0).apply {
                        dataType = org.javarosa.core.model.Constants.DATATYPE_TEXT
                        meta.addChild(this)
                    }
                require(deprecated.isLeaf && deprecated.value == null &&
                    deprecated.dataType == org.javarosa.core.model.Constants.DATATYPE_TEXT)
                deprecated.setValue(StringData(binding.sourceInstanceId))
                require(deprecated.value?.value == binding.sourceInstanceId)
            }
            require(meta.numChildren == expectedMetadata.size &&
                (0 until meta.numChildren).map { meta.getChildAt(it).name }.toSet() == expectedMetadata.keys)
            val instanceRef = TreeReference.rootRef().apply {
                add(root, 0)
                add("meta", 0)
                add("instanceID", 0)
            }
            val instance = requireNotNull(form.instance.resolveReference(instanceRef))
            require(instance.isLeaf && instance.value == null)
            require(binding.reservedInstanceId.startsWith("uuid:") &&
                UUID.fromString(binding.reservedInstanceId.removePrefix("uuid:")).toString() ==
                    binding.reservedInstanceId.removePrefix("uuid:"))
            form.setValue(StringData(binding.reservedInstanceId), instanceRef, true)
            require(form.instance.resolveReference(instanceRef)?.value?.value == binding.reservedInstanceId)
            assertScalars()
            for ((leaf, token) in references.values) {
                require(token != null && form.instance.resolveReference(leaf)?.value?.value == token)
            }
            if (onSerialized != null) {
                // Native serializer from the ordinary Collect save path.
                // This bounded gate deliberately admits only text repeat
                // leaves until typed lexical round-trip rules are qualified.
                require(group.fields.all { it.type == PrivateScalarType.TEXT })
                require(form.submissionProfile == null || form.submissionProfile.ref == null)
                val payload = org.javarosa.model.xform.XFormSerializingVisitor(false)
                    .createSerializedPayload(form.instance) as
                    org.javarosa.core.services.transport.payload.ByteArrayPayload
                val bytes = payload.payloadStream.use { input ->
                    val output = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    try {
                        while (true) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            require(output.size() + n <= 8_388_608)
                            output.write(buffer, 0, n)
                        }
                        output.toByteArray()
                    } finally { buffer.fill(0) }
                }
                try {
                    val expectedDirect = directAnswers + schema.directImagePaths.associateWith {
                        requireNotNull(references.getValue("direct" to it).second)
                    }
                    val expectedRows = active.map { row ->
                        savedAnswers.getValue(row.rowId) + group.imagePaths.associateWith {
                            requireNotNull(references.getValue(row.rowId to it).second)
                        }
                    }
                    onSerialized(ProtectedNativeXmlVerifier.verify(bytes, binding, root, name,
                        expectedDirect, expectedRows, references.mapValues { requireNotNull(it.value.second) },
                        useAttachmentNames), bytes)
                } finally { bytes.fill(0) }
            }
            form.setValue(null, instanceRef, true)
            require(form.instance.resolveReference(instanceRef)?.value == null)
        }
        // Clearing these temporary references cannot touch encrypted assets
        // or change recovered scalar values in the same native instance tree.
        for ((leaf, token) in references.values) {
            if (token != null) form.setValue(null, leaf, true)
            require(form.instance.resolveReference(leaf)?.value == null)
        }
        assertScalars()
        require(ordinals.keys == receipts.keys)
        ordinals.toMap()
    }

    internal fun validateDirectText(values: Map<String, String>): Map<String, String> =
        withNativeTarget { form ->
            require(values.keys == schema.directTextPaths.toSet() && values.isNotEmpty())
            applyDirectText(form, values)
            values.toMap()
        }

    private fun directRef(form: FormDef, path: String): TreeReference =
        TreeReference.rootRef().apply {
            require(path.startsWith("/" + form.instance.root.name + "/") &&
                path.removePrefix("/" + form.instance.root.name + "/").let {
                    it.isNotBlank() && '/' !in it
                })
            add(form.instance.root.name, 0)
            add(path.substringAfterLast('/'), 0)
        }

    private fun applyDirectText(
        form: FormDef, values: Map<String, String>
    ): List<Triple<TreeReference, PrivateScalarType, IAnswerData?>> {
        require(values.isEmpty() || values.keys == schema.directTextPaths.toSet())
        return schema.directTextPaths.map { path ->
            val leaf = directRef(form, path)
            val field = requireNotNull(form.instance.resolveReference(leaf))
            require(field.isLeaf && field.value == null &&
                field.dataType == PrivateScalarType.TEXT.javaRosaType)
            val value = values[path].orEmpty()
            require(value.length <= MAX_TEXT && '\u0000' !in value)
            val answer = if (value.isEmpty()) null else StringData(value)
            if (answer != null) {
                require(form.evaluateConstraint(leaf, answer))
                form.setValue(answer, leaf, true)
                require(form.instance.resolveReference(leaf)?.value?.value == value)
            }
            Triple(leaf, PrivateScalarType.TEXT, answer)
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
