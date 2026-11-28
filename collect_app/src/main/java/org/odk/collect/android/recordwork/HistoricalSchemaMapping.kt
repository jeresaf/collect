package org.odk.collect.android.recordwork

import org.odk.collect.android.formmanagement.metadata.FormMetadataParser
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.ByteArrayInputStream
import java.security.MessageDigest

/**
 * M4C-2A: a version-bound human mapping proposal; NOT a transformed instance.
 * Source answers are NEVER exposed by this schema-only component.
 */
enum class MappingRisk {
    NO_HISTORICAL_SCHEMA, SOURCE_SCHEMA_MISMATCH, DIFFERENT_FORM_FOLLOW_UP,
    MISSING_SOURCE_FIELD, SOURCE_PATH_REMOVED, TYPE_CHANGED, REPEAT,
    STATIC_SELECT, DYNAMIC_SELECT, CHOICE_LIST_CHANGED, MEDIA, CALCULATION,
    RELEVANCE_OR_CONSTRAINT, REQUIRED, UNBOUND_FIELD, EXPLICIT_REVIEW_REQUIRED
}

enum class MappingDecision { REENTER, LEAVE_EMPTY, REQUEST_REVIEWED_TRANSFER }

data class SchemaField(
    val path: String,
    val type: String,
    val isRepeat: Boolean,
    val choiceValues: Set<String>?,
    val dynamicChoices: Boolean,
    val media: Boolean,
    val calculated: Boolean,
    val relevantOrConstrained: Boolean,
    val required: Boolean,
    val bindingSignature: String,
    val choiceMeaningSignature: String? = null
)

data class TargetFieldCandidate(
    val targetPath: String,
    val sourcePath: String?,
    val risks: Set<MappingRisk>,
    val safeScalarProposal: Boolean
)

/**
 * Can be used to show a review UI, but must not be interpreted as permission
 * to launch a form, prefill, write a draft, or submit a record.
 */
data class HistoricalMappingPlan(
    val workId: String,
    val sourceSubmissionId: String,
    val targetFormVersionId: String,
    val workType: RecordWorkType,
    val sourceXFormSha256: String?,
    val targetXFormSha256: String,
    val sourceFormId: String?,
    val sourceVersion: String?,
    val targetFormId: String,
    val targetVersion: String,
    val candidates: List<TargetFieldCandidate>,
    val removedSourcePaths: Set<String>,
    val globalRisks: Set<MappingRisk>,
    val canPrefill: Boolean = false,
    val canEdit: Boolean = false,
    val canSubmit: Boolean = false
) {
    init {
        require(!canPrefill && !canEdit && !canSubmit) {
            "A schema review cannot authorize an editable form or an upload"
        }
    }
}

/** A review-only, value-free, in-memory description of operator decisions. */
data class MappingReviewSnapshot(
    val workId: String,
    val sourceXFormSha256: String?,
    val targetXFormSha256: String,
    val decisions: Map<String, MappingDecision>,
    val reviewedEveryTargetPath: Boolean,
    val allTransfersNeedJavaRosa: Boolean = true,
    val canSubmit: Boolean = false
) {
    init {
        require(!canSubmit && allTransfersNeedJavaRosa) {
            "An operator path review does not validate answers under JavaRosa"
        }
    }
}

/**
 * Decisions are intentionally not persisted and not convertible to a
 * RecordMappingApproval. Any future editor must bind the decisions, exact
 * schema hashes, READY server work item, source instance, and JavaRosa check.
 */
class HistoricalMappingReview(private val plan: HistoricalMappingPlan) {
    private val decisions = linkedMapOf<String, MappingDecision>()

    fun decide(targetPath: String, decision: MappingDecision) {
        val field = plan.candidates.singleOrNull { it.targetPath == targetPath }
            ?: throw IllegalArgumentException("Unknown target field path")
        if (decision == MappingDecision.REQUEST_REVIEWED_TRANSFER) {
            require(field.safeScalarProposal) {
                "Unsafe automatic mapping request; re-enter this answer"
            }
        }
        decisions[targetPath] = decision
    }

    fun snapshot(): MappingReviewSnapshot = MappingReviewSnapshot(
        workId = plan.workId,
        sourceXFormSha256 = plan.sourceXFormSha256,
        targetXFormSha256 = plan.targetXFormSha256,
        decisions = decisions.toMap(),
        reviewedEveryTargetPath = decisions.size == plan.candidates.size
    )
}

/**
 * Reconciles two *exact* XForm definitions. A source response with no
 * historical schema is deliberately non-transferable; we never assume
 * source-v1 had the same meaning as target-v2.
 *
 * Fails closed for unexpected XML, unbound references or unsupported nesting.
 * This is schema matching, NOT JavaRosa FormDef/instance evaluation.
 */
object HistoricalSchemaMappingEngine {
    private const val MAX_XML = 8_388_608
    private const val MAX_NODES = 48_000
    private const val MAX_DEPTH = 80
    private val mediaTypes = setOf("binary", "image", "audio", "video", "file", "anyuri")

    fun plan(
        workId: String,
        sourceSubmissionId: String,
        targetFormVersionId: String,
        workType: RecordWorkType,
        sourceXml: String,
        sourceXFormXml: String?,
        targetXFormXml: String,
        targetFormId: String,
        targetFormVersion: String
    ): HistoricalMappingPlan {
        require(targetFormId.isNotBlank() && targetFormVersion.isNotBlank())
        val target = parseSchema(targetXFormXml)
        require(target.formId == targetFormId && target.version == targetFormVersion) {
            "Authenticated target XForm metadata mismatch"
        }
        val sourceInstance = parseRoot(sourceXml)
        val sourceId = sourceInstance.getAttribute("id")
        val sourceVersion = sourceInstance.getAttribute("version")
        val missingOriginal = sourceXFormXml.isNullOrBlank()
        val original = if (missingOriginal) null else parseSchema(requireNotNull(sourceXFormXml))
        val global = mutableSetOf(MappingRisk.EXPLICIT_REVIEW_REQUIRED)
        if (original == null) global += MappingRisk.NO_HISTORICAL_SCHEMA
        if (workType == RecordWorkType.FOLLOW_UP) {
            global += MappingRisk.DIFFERENT_FORM_FOLLOW_UP
        }
        if (original != null &&
            (original.formId != sourceId || original.version != sourceVersion)) {
            global += MappingRisk.SOURCE_SCHEMA_MISMATCH
        }
        if (workType == RecordWorkType.AMENDMENT && sourceId != target.formId) {
            global += MappingRisk.SOURCE_SCHEMA_MISMATCH
        }
        val safeIdentity = original != null &&
            original.formId == sourceId && original.version == sourceVersion &&
            sourceId == target.formId && workType == RecordWorkType.AMENDMENT
        val fields = target.fields.values.map { destination ->
            val old = if (safeIdentity) original!!.fields[destination.path] else null
            val risks = mutableSetOf<MappingRisk>()
            if (original == null) risks += MappingRisk.NO_HISTORICAL_SCHEMA
            if (!safeIdentity) risks += MappingRisk.SOURCE_SCHEMA_MISMATCH
            if (workType == RecordWorkType.FOLLOW_UP) {
                risks += MappingRisk.DIFFERENT_FORM_FOLLOW_UP
            }
            if (old == null) risks += MappingRisk.MISSING_SOURCE_FIELD
            if (old != null && old.type != destination.type) risks += MappingRisk.TYPE_CHANGED
            if (old?.type == "unbound" || destination.type == "unbound") {
                risks += MappingRisk.UNBOUND_FIELD
            }
            if (destination.isRepeat || old?.isRepeat == true) risks += MappingRisk.REPEAT
            if (destination.choiceValues != null || old?.choiceValues != null) {
                risks += if (destination.dynamicChoices || old?.dynamicChoices == true) {
                    MappingRisk.DYNAMIC_SELECT
                } else {
                    MappingRisk.STATIC_SELECT
                }
                if (old?.choiceValues != destination.choiceValues ||
                    old?.choiceMeaningSignature != destination.choiceMeaningSignature) {
                    risks += MappingRisk.CHOICE_LIST_CHANGED
                }
            }
            if (destination.media || old?.media == true) risks += MappingRisk.MEDIA
            if (destination.calculated || old?.calculated == true) {
                risks += MappingRisk.CALCULATION
            }
            if (destination.relevantOrConstrained || old?.relevantOrConstrained == true ||
                (old != null && old.bindingSignature != destination.bindingSignature)) {
                risks += MappingRisk.RELEVANCE_OR_CONSTRAINT
            }
            if (destination.required || old?.required == true) risks += MappingRisk.REQUIRED
            val canPropose = safeIdentity && old != null && risks.isEmpty()
            TargetFieldCandidate(
                targetPath = destination.path,
                sourcePath = old?.path,
                risks = risks,
                safeScalarProposal = canPropose
            )
        }
        val removed = if (safeIdentity) {
            requireNotNull(original).fields.keys - target.fields.keys
        } else emptySet()

        return HistoricalMappingPlan(
            workId, sourceSubmissionId, targetFormVersionId, workType,
            sourceXFormXml?.takeIf(String::isNotBlank)?.let(::sha256),
            sha256(targetXFormXml), original?.formId, original?.version,
            target.formId, target.version, fields,
            removed, global
        )
    }

    /**
     * Explicit one-to-one mappings, never inferred from labels or order.
     * Reparse exact schema bytes; no submitted answer values enter this path.
     * The result is advisory only and intentionally cannot alter the existing
     * per-field approval, grant, editor or submission state.
     */
    fun inspectExplicitRules(
        plan: HistoricalMappingPlan,
        sourceXFormXml: String,
        targetXFormXml: String,
        rules: List<ExplicitFieldRule>
    ): ExplicitMappingReview {
        require(plan.workType == RecordWorkType.AMENDMENT &&
            plan.sourceFormId == plan.targetFormId &&
            plan.globalRisks.none { it != MappingRisk.EXPLICIT_REVIEW_REQUIRED }) {
            "Explicit historical mapping requires matching amendment identity"
        }
        require(plan.sourceXFormSha256 == sha256(sourceXFormXml) &&
            plan.targetXFormSha256 == sha256(targetXFormXml)) { "Schema drift" }
        val source = parseSchema(sourceXFormXml)
        val target = parseSchema(targetXFormXml)
        require(source.formId == plan.sourceFormId && source.version == plan.sourceVersion &&
            target.formId == plan.targetFormId && target.version == plan.targetVersion &&
            target.fields.keys == plan.candidates.map { it.targetPath }.toSet()) {
            "Schema inventory differs from plan"
        }
        val manifest = ExplicitMappingManifest(plan.workId, plan.sourceSubmissionId,
            plan.targetFormVersionId, requireNotNull(plan.sourceXFormSha256),
            plan.targetXFormSha256, rules)
        val findings = manifest.rules.map { rule ->
            val old = source.fields[rule.sourcePath]
            val next = target.fields[rule.targetPath]
            val verdict = when {
                old == null || next == null -> ExplicitRuleVerdict.BLOCKED_UNKNOWN_PATH
                rule.sourcePath != rule.targetPath &&
                    (rule.sourcePath in target.fields || rule.targetPath in source.fields) ->
                    ExplicitRuleVerdict.BLOCKED_RENAME_COLLISION
                scalarType(old.type) != rule.type || scalarType(next.type) != rule.type ->
                    ExplicitRuleVerdict.BLOCKED_TYPE_OR_UNSUPPORTED
                listOf(old, next).any { it.isRepeat || it.media || it.calculated ||
                    it.relevantOrConstrained || it.required || it.dynamicChoices ||
                    it.choiceValues != null } ->
                    ExplicitRuleVerdict.BLOCKED_COMPLEX_RULES
                else -> ExplicitRuleVerdict.REQUIRES_NATIVE_AND_SEMANTIC_REVIEW
            }
            ExplicitRuleFinding(rule, verdict)
        }
        return ExplicitMappingReview(manifest, findings)
    }

    private fun scalarType(type: String): ExplicitScalarType? = when (type) {
        "string" -> ExplicitScalarType.TEXT
        "int", "integer" -> ExplicitScalarType.INTEGER
        "decimal" -> ExplicitScalarType.DECIMAL
        "date" -> ExplicitScalarType.DATE
        else -> null
    }

    private data class Schema(
        val formId: String, val version: String,
        val fields: Map<String, SchemaField>
    )

    private fun parseSchema(xml: String): Schema {
        val doc = parseDocument(xml)
        val metadata = FormMetadataParser.readMetadata(
            ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8))
        )
        val primary = descendants(doc.documentElement)
            .filter { local(it) == "instance" && (it.parentNode as? Element)?.let(::local) == "model" }
            .firstOrNull()
            ?: throw IllegalArgumentException("No primary instance")
        val root = elements(primary).singleOrNull()
            ?: throw IllegalArgumentException("Invalid primary instance")
        val id = root.getAttribute("id")
        val version = root.getAttribute("version")
        require(id.isNotBlank() && version.isNotBlank() &&
            metadata.id == id && metadata.version == version) {
            "XForm primary instance identity was changed"
        }
        // Unsupported relative references must never be silently dropped:
        // dropping a repeat/bind/select would incorrectly mark a path safe.
        for (element in descendants(doc.documentElement)) {
            if (local(element) in setOf("repeat", "bind", "select", "select1", "upload")) {
                val raw = element.getAttribute("ref")
                    .ifBlank { element.getAttribute("nodeset") }.trim()
                require(raw.matches(SIMPLE_ABSOLUTE_PATH)) {
                    "Unsupported relative or missing form path"
                }
            }
        }
        // Reject ambiguity before building maps: toMap() would silently
        // discard an earlier declaration, including its validation rules.
        val binds = uniqueReferences(doc.documentElement, setOf("bind"))
        val repeatPaths = uniqueReferences(doc.documentElement, setOf("repeat")).keys
        val choices = uniqueReferences(doc.documentElement, setOf("select", "select1"))
        val uploads = uniqueReferences(doc.documentElement, setOf("upload")).keys
        require(choices.keys.intersect(uploads).isEmpty()) {
            "Conflicting choice and media controls"
        }

        val found = linkedMapOf<String, SchemaField>()
        fun collect(node: Element, path: String, depth: Int) {
            require(depth <= MAX_DEPTH)
            if (local(node) == "meta") return
            val kids = elements(node)
            if (kids.isEmpty()) {
                if (path != "/" + local(root)) {
                    val bind = binds[path]
                    val choice = choices[path]
                    val staticItems = choice?.let { c ->
                        elements(c).filter { local(it) == "item" }.map { item ->
                            val value = elements(item).singleOrNull { local(it) == "value" }
                                ?: throw IllegalArgumentException("Ambiguous static choice value")
                            val label = elements(item).singleOrNull { local(it) == "label" }
                            require(value.textContent.isNotBlank()) { "Empty static choice code" }
                            // Do not display or persist labels in findings; exact code,
                            // inline caption and translation-reference changes are risk.
                            listOf(value.textContent, label?.textContent.orEmpty(),
                                label?.getAttribute("ref").orEmpty())
                        }.also { items ->
                            require(items.map { it[0] }.toSet().size == items.size) {
                                "Duplicate static choice code"
                            }
                        }
                    }
                    val hasItems = staticItems?.map { it[0] }?.toSet()
                    val choiceMeaning = staticItems?.let { items ->
                        sha256(local(requireNotNull(choice)) + ":" + items.joinToString("") { item ->
                            item.joinToString("") { value -> "${value.length}:$value" }
                        })
                    }
                    val dynamic = choice?.let { c ->
                        descendants(c).any { local(it) == "itemset" } || hasItems.isNullOrEmpty()
                    } ?: false
                    val type = if (bind == null) "unbound" else
                        bind.getAttribute("type").lowercase().ifBlank { "string" }
                    // A group's bind may impose relevance, readonly or
                    // constraints on otherwise uncomplicated descendants.
                    val applicable = binds.entries.filter { (boundPath, _) ->
                        path == boundPath || path.startsWith("$boundPath/")
                    }.sortedBy { it.key }
                    val calculated = applicable.any { (_, rule) ->
                        rule.getAttribute("calculate").isNotBlank() ||
                            rule.getAttribute("readonly").trim().let {
                                it.isNotBlank() && it !in setOf("false", "false()")
                            }
                    }
                    val relevant = applicable.any { (_, rule) ->
                        rule.getAttribute("relevant").isNotBlank() ||
                            rule.getAttribute("constraint").isNotBlank()
                    }
                    // Empty-rule group binds do not change the effective
                    // signature. All conditional required expressions are risk.
                    val required = applicable.any { (_, rule) ->
                        rule.getAttribute("required").trim().let {
                            it.isNotBlank() && it !in setOf("false", "false()")
                        }
                    }
                    val signature = sha256(applicable.joinToString("") { (boundPath, rule) ->
                        val values = listOf("relevant", "constraint", "required", "calculate", "readonly")
                            .map { rule.getAttribute(it) }
                        if (values.all { it.isBlank() }) "" else
                            (listOf(boundPath) + values).joinToString("") { "${it.length}:$it" }
                    })
                    require(path !in found) { "Ambiguous duplicate field path" }
                    found[path] = SchemaField(
                        path, type, repeatPaths.any { path == it || path.startsWith(it + "/") },
                        if (choice != null) hasItems else null,
                        dynamic,
                        path in uploads || type in mediaTypes,
                        calculated, relevant, required,
                        signature, choiceMeaning
                    )
                }
            } else kids.forEach { collect(it, path + "/" + local(it), depth + 1) }
        }
        collect(root, "/" + local(root), 0)
        require(found.size <= MAX_NODES / 6)
        return Schema(id, version, found)
    }

    private fun parseRoot(xml: String): Element = parseDocument(xml).documentElement

    private fun parseDocument(xml: String): Document {
        require(xml.isNotBlank() && xml.length <= MAX_XML) { "XML exceeds size limit" }
        val doc = RecordWorkSafeXml.parse(xml, MAX_XML, MAX_DEPTH, MAX_NODES)
        val pending = java.util.ArrayDeque<Pair<Element, Int>>()
        pending.add(doc.documentElement to 0)
        var total = 0
        while (pending.isNotEmpty()) {
            val (element, depth) = pending.removeLast()
            require(depth <= MAX_DEPTH && ++total <= MAX_NODES) { "XML structure too complex" }
            elements(element).forEach { pending.add(it to depth + 1) }
        }
        return doc
    }

    private fun elements(e: Element): List<Element> =
        (0 until e.childNodes.length).mapNotNull { i ->
            e.childNodes.item(i).takeIf { it.nodeType == Node.ELEMENT_NODE } as? Element
        }

    private fun descendants(e: Element): Sequence<Element> = sequence {
        yield(e)
        elements(e).forEach { yieldAll(descendants(it)) }
    }

    private fun uniqueReferences(root: Element, kinds: Set<String>): Map<String, Element> {
        val result = linkedMapOf<String, Element>()
        descendants(root).filter { local(it) in kinds }.forEach { element ->
            val path = requireNotNull(reference(element))
            require(path.matches(SIMPLE_ABSOLUTE_PATH) && path !in result) {
                "Duplicate or unsupported form reference"
            }
            result[path] = element
        }
        return result
    }

    private val SIMPLE_ABSOLUTE_PATH =
        Regex("/[A-Za-z_][A-Za-z0-9_.-]*(/[A-Za-z_][A-Za-z0-9_.-]*){0,79}")

    private fun reference(e: Element): String? =
        e.getAttribute("ref").ifBlank { e.getAttribute("nodeset") }
            .trim().takeIf { it.startsWith("/") }

    private fun local(e: Element): String = e.localName ?: e.tagName.substringAfter(':')

    private fun sha256(s: String): String = MessageDigest.getInstance("SHA-256")
        .digest(s.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
