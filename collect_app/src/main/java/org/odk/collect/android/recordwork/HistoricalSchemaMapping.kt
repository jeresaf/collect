package org.odk.collect.android.recordwork

import org.odk.collect.android.formmanagement.metadata.FormMetadataParser
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node
import org.xml.sax.InputSource
import java.io.ByteArrayInputStream
import java.io.StringReader
import java.security.MessageDigest
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

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
    val bindingSignature: String
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
            if (destination.isRepeat || old?.isRepeat == true) risks += MappingRisk.REPEAT
            if (destination.choiceValues != null || old?.choiceValues != null) {
                risks += if (destination.dynamicChoices || old?.dynamicChoices == true) {
                    MappingRisk.DYNAMIC_SELECT
                } else {
                    MappingRisk.STATIC_SELECT
                }
                if (old?.choiceValues != destination.choiceValues) {
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
        val binds = descendants(doc.documentElement).filter { local(it) == "bind" }
            .mapNotNull { e -> reference(e)?.let { it to e } }.toMap()
        val repeatPaths = descendants(doc.documentElement)
            .filter { local(it) == "repeat" }.mapNotNull(::reference).toSet()
        val choices = descendants(doc.documentElement)
            .filter { local(it) == "select" || local(it) == "select1" }
            .mapNotNull { e -> reference(e)?.let { it to e } }.toMap()
        val uploads = descendants(doc.documentElement)
            .filter { local(it) == "upload" }.mapNotNull(::reference).toSet()

        val found = linkedMapOf<String, SchemaField>()
        fun collect(node: Element, path: String, depth: Int) {
            require(depth <= MAX_DEPTH)
            if (local(node) == "meta") return
            val kids = elements(node)
            if (kids.isEmpty()) {
                if (path != "/" + local(root)) {
                    val bind = binds[path]
                    val choice = choices[path]
                    val hasItems = choice?.let { c ->
                        descendants(c).filter { local(it) == "value" }.map { it.textContent.orEmpty() }
                            .filter(String::isNotBlank).toSet()
                    }
                    val dynamic = choice?.let { c ->
                        descendants(c).any { local(it) == "itemset" } || hasItems.isNullOrEmpty()
                    } ?: false
                    val type = bind?.getAttribute("type")?.lowercase().orEmpty().ifBlank { "string" }
                    val calculated = bind?.getAttribute("calculate").orEmpty().isNotBlank() ||
                        bind?.getAttribute("readonly") in setOf("true", "true()")
                    val relevant = bind?.getAttribute("relevant").orEmpty().isNotBlank() ||
                        bind?.getAttribute("constraint").orEmpty().isNotBlank()
                    val signature = listOf(
                        bind?.getAttribute("relevant").orEmpty(),
                        bind?.getAttribute("constraint").orEmpty(),
                        bind?.getAttribute("required").orEmpty(),
                        bind?.getAttribute("calculate").orEmpty()
                    ).joinToString("|")
                    require(path !in found) { "Ambiguous duplicate field path" }
                    found[path] = SchemaField(
                        path, type, repeatPaths.any { path == it || path.startsWith(it + "/") },
                        if (choice != null) hasItems else null,
                        dynamic,
                        path in uploads || type in mediaTypes,
                        calculated, relevant, bind?.getAttribute("required") in setOf("true", "true()"),
                        signature
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
        val factory = DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = true
        factory.isXIncludeAware = false
        factory.isExpandEntityReferences = false
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false)
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false)
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
        val doc = factory.newDocumentBuilder().apply {
            setEntityResolver { _, _ -> throw IllegalArgumentException("Entity resolution forbidden") }
        }.parse(InputSource(StringReader(xml)))
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

    private fun reference(e: Element): String? =
        e.getAttribute("ref").ifBlank { e.getAttribute("nodeset") }
            .trim().takeIf { it.startsWith("/") }

    private fun local(e: Element): String = e.localName ?: e.tagName.substringAfter(':')

    private fun sha256(s: String): String = MessageDigest.getInstance("SHA-256")
        .digest(s.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
