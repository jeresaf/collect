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
 * M4C-1 deliberately read-only: no answer extraction, migration, persistence,
 * JavaRosa form filling, or authority to set RecordMappingApproval flags.
 *
 * A matching XML path is not proof that answers can be carried to a new form.
 * Full JavaRosa evaluation and operator acceptance are M4C-2 release gates.
 */
enum class MappingIssue {
    SOURCE_FIELD_REMOVED,
    TARGET_FIELD_ADDED,
    CROSS_FORM_FOLLOW_UP,
    REPEAT_REVIEW,
    STATIC_CHOICE_REVIEW,
    DYNAMIC_CHOICE_REVIEW,
    CHOICE_VALUE_NOT_IN_TARGET,
    MEDIA_REVIEW,
    CALCULATED_OR_READ_ONLY,
    RELEVANCE_OR_CONSTRAINT,
    UNSUPPORTED_BIND_REFERENCE,
    SOURCE_XML_ID_MISMATCH,
    SOURCE_SCHEMA_UNAVAILABLE,
    JAVAROSA_EVALUATION_REQUIRED,
    EXPLICIT_OPERATOR_APPROVAL_REQUIRED
}

data class MappingFinding(
    val path: String,
    val issues: Set<MappingIssue>
)

data class CrossVersionAssessment(
    val workId: String,
    val sourceSubmissionId: String,
    val targetFormVersionId: String,
    val workType: RecordWorkType,
    val sourceSha256: String,
    val targetXFormSha256: String,
    val sourceVersion: String?,
    val targetVersion: String,
    val sourceFieldCount: Int,
    val targetFieldCount: Int,
    val unchangedPathCount: Int,
    val findings: List<MappingFinding>,
    val globalIssues: Set<MappingIssue>,
    val canPrefill: Boolean = false,
    val canEdit: Boolean = false,
    val canSubmit: Boolean = false
) {
    init {
        require(!canPrefill && !canEdit && !canSubmit) {
            "M4C-1 assessment is not an editing or submission authorization"
        }
    }

    fun issueCounts(): Map<MappingIssue, Int> =
        (findings.flatMap { it.issues.toList() } + globalIssues).groupingBy { it }.eachCount()
}

/**
 * Produces a data-minimized impact report from one server-authorized M3 prepare
 * response. Paths and risk categories only: NEVER include answer values.
 * Raw XML is processed only in memory and discarded by the caller.
 */
object CrossVersionAssessmentEngine {
    private const val MAX_XML_CHARS = 8_388_608
    private const val MAX_FIELDS = 8_000
    private const val MAX_DEPTH = 80
    private val mediaTypes = setOf("binary", "image", "audio", "video", "file", "anyuri")
    private val answerContainers = setOf("meta")

    fun assess(
        workId: String,
        sourceSubmissionId: String,
        targetFormVersionId: String,
        workType: RecordWorkType,
        sourceXml: String,
        targetXFormXml: String,
        expectedTargetFormId: String,
        expectedTargetVersion: String
    ): CrossVersionAssessment {
        require(sourceXml.isNotBlank() && targetXFormXml.isNotBlank())
        require(sourceXml.length <= MAX_XML_CHARS && targetXFormXml.length <= MAX_XML_CHARS)
        require(expectedTargetFormId.isNotBlank() && expectedTargetVersion.isNotBlank())

        val sourceDoc = hardenedParse(sourceXml)
        val targetDoc = hardenedParse(targetXFormXml)
        val sourceRoot = sourceDoc.documentElement
        val targetRoot = mainInstanceRoot(targetDoc)

        // Run the ODK JavaRosa XML metadata reader *as an independent check*
        // on the target. This does not replace full FormDef/JavaRosa validation.
        val formMetadata = FormMetadataParser.readMetadata(
            ByteArrayInputStream(targetXFormXml.toByteArray(Charsets.UTF_8))
        )
        require(formMetadata.id == expectedTargetFormId) {
            "Target XForm ID differs from the server-authorized version"
        }
        require(formMetadata.version == expectedTargetVersion) {
            "Target XForm version differs from the server-authorized version"
        }
        require(targetRoot.getAttribute("id") == expectedTargetFormId &&
            targetRoot.getAttribute("version") == expectedTargetVersion) {
            "Target primary instance does not match its metadata"
        }

        val source = inventory(sourceRoot)
        val target = inventory(targetRoot)
        val repeats = extractRepeatPaths(targetDoc)
        val binds = extractBinds(targetDoc)
        val choices = extractChoices(targetDoc)
        val findings = mutableMapOf<String, MutableSet<MappingIssue>>()
        val global = mutableSetOf(
            MappingIssue.SOURCE_SCHEMA_UNAVAILABLE,
            MappingIssue.JAVAROSA_EVALUATION_REQUIRED,
            MappingIssue.EXPLICIT_OPERATOR_APPROVAL_REQUIRED
        )
        if (workType == RecordWorkType.FOLLOW_UP) {
            global += MappingIssue.CROSS_FORM_FOLLOW_UP
        } else if (sourceRoot.getAttribute("id") != expectedTargetFormId) {
            global += MappingIssue.SOURCE_XML_ID_MISMATCH
        }

        fun flag(path: String, issue: MappingIssue) {
            findings.getOrPut(path) { mutableSetOf() }.add(issue)
        }

        (source.keys - target.keys).forEach { flag(it, MappingIssue.SOURCE_FIELD_REMOVED) }
        (target.keys - source.keys).forEach { flag(it, MappingIssue.TARGET_FIELD_ADDED) }

        for (path in (source.keys intersect target.keys)) {
            if (source.getValue(path).size != 1 ||
                repeats.any { path == it || path.startsWith("$it/") }) {
                flag(path, MappingIssue.REPEAT_REVIEW)
            }
            val bind = binds[path]
            if (bind != null) {
                if (bind.type.lowercase() in mediaTypes) flag(path, MappingIssue.MEDIA_REVIEW)
                if (bind.calculate.isNotBlank() || bind.readonly == "true" ||
                    bind.readonly == "true()") flag(path, MappingIssue.CALCULATED_OR_READ_ONLY)
                if (bind.relevant.isNotBlank() || bind.constraint.isNotBlank()) {
                    flag(path, MappingIssue.RELEVANCE_OR_CONSTRAINT)
                }
            }
            val choice = choices[path]
            if (choice != null) {
                if (choice.dynamic) {
                    flag(path, MappingIssue.DYNAMIC_CHOICE_REVIEW)
                } else {
                    flag(path, MappingIssue.STATIC_CHOICE_REVIEW)
                    // Never display answer values. Only report if the old
                    // selection would no longer exist in the target choices.
                    val raw = source.getValue(path).singleOrNull().orEmpty().trim()
                    if (raw.isNotBlank() &&
                        raw.split(Regex("\\s+")).any { it !in choice.allowed }) {
                        flag(path, MappingIssue.CHOICE_VALUE_NOT_IN_TARGET)
                    }
                }
            }
        }

        for ((path, bind) in binds) {
            if (!path.startsWith("/${localName(targetRoot)}/") ||
                (path !in target && path != "/${localName(targetRoot)}")) {
                flag(path, MappingIssue.UNSUPPORTED_BIND_REFERENCE)
            }
            if (path in target && bind.type.lowercase() in mediaTypes) {
                flag(path, MappingIssue.MEDIA_REVIEW)
            }
        }
        // Always surface repeat/media/choice requirements even when the source
        // has no matching answer; missing target structures are still reviewed.
        repeats.forEach { flag(it, MappingIssue.REPEAT_REVIEW) }
        for ((path, choice) in choices) {
            flag(path, if (choice.dynamic) MappingIssue.DYNAMIC_CHOICE_REVIEW
                       else MappingIssue.STATIC_CHOICE_REVIEW)
        }

        require(findings.size <= MAX_FIELDS) { "Assessment exceeds safe complexity" }
        return CrossVersionAssessment(
            workId = workId,
            sourceSubmissionId = sourceSubmissionId,
            targetFormVersionId = targetFormVersionId,
            workType = workType,
            sourceSha256 = sha256(sourceXml),
            targetXFormSha256 = sha256(targetXFormXml),
            sourceVersion = sourceRoot.getAttribute("version").ifBlank { null },
            targetVersion = expectedTargetVersion,
            sourceFieldCount = source.size,
            targetFieldCount = target.size,
            unchangedPathCount = (source.keys intersect target.keys).size,
            findings = findings.toSortedMap().map { MappingFinding(it.key, it.value.toSet()) },
            globalIssues = global.toSet()
        )
    }

    private data class BindInfo(
        val type: String, val calculate: String, val readonly: String,
        val relevant: String, val constraint: String
    )
    private data class ChoiceInfo(val dynamic: Boolean, val allowed: Set<String>)

    private fun hardenedParse(xml: String): Document {
        val factory = DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = true
        factory.isXIncludeAware = false
        factory.isExpandEntityReferences = false
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false)
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false)
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
        return factory.newDocumentBuilder().apply {
            setEntityResolver { _, _ -> throw IllegalArgumentException("External entities forbidden") }
        }.parse(InputSource(StringReader(xml))).also {
            require(it.documentElement != null) { "Missing root" }
        }
    }

    private fun mainInstanceRoot(doc: Document): Element {
        val instances = descendants(doc.documentElement).filter { localName(it) == "instance" }
        val primary = instances.firstOrNull { it.parentNode is Element &&
            localName(it.parentNode as Element) == "model" }
            ?: throw IllegalArgumentException("Missing target model primary instance")
        return children(primary).singleOrNull()
            ?: throw IllegalArgumentException("Invalid target primary instance")
    }

    private fun inventory(root: Element): Map<String, List<String>> {
        val paths = mutableMapOf<String, MutableList<String>>()
        var nodes = 0
        fun visit(node: Element, path: String, depth: Int) {
            require(depth <= MAX_DEPTH) { "XML nesting is too deep" }
            if (++nodes > MAX_FIELDS * 4) throw IllegalArgumentException("Too many nodes")
            val childElements = children(node).filterNot { localName(it) in answerContainers }
            if (childElements.isEmpty() && path.count { it == '/' } > 1) {
                // Contents are transient, used only for in-memory choice checks.
                paths.getOrPut(path) { mutableListOf() }.add(node.textContent.orEmpty())
            } else {
                childElements.forEach { child ->
                    visit(child, "$path/${localName(child)}", depth + 1)
                }
            }
        }
        visit(root, "/${localName(root)}", 0)
        require(paths.size <= MAX_FIELDS) { "Excessive field count" }
        return paths
    }

    private fun extractBinds(doc: Document): Map<String, BindInfo> =
        descendants(doc.documentElement)
            .filter { localName(it) == "bind" }
            .mapNotNull { b ->
                val path = (b.getAttribute("nodeset").ifBlank { b.getAttribute("ref") })
                    .trim()
                if (path.isBlank()) null else path to BindInfo(
                    type = b.getAttribute("type"),
                    calculate = b.getAttribute("calculate"),
                    readonly = b.getAttribute("readonly"),
                    relevant = b.getAttribute("relevant"),
                    constraint = b.getAttribute("constraint")
                )
            }.toMap()

    private fun extractRepeatPaths(doc: Document): Set<String> =
        descendants(doc.documentElement)
            .filter { localName(it) == "repeat" }
            .mapNotNull { it.getAttribute("nodeset")
                .ifBlank { it.getAttribute("ref") }.takeIf(String::isNotBlank) }
            .toSet()

    private fun extractChoices(doc: Document): Map<String, ChoiceInfo> {
        val result = mutableMapOf<String, ChoiceInfo>()
        for (control in descendants(doc.documentElement)) {
            if (localName(control) !in setOf("select", "select1")) continue
            val path = control.getAttribute("ref").ifBlank { control.getAttribute("nodeset") }
            if (path.isBlank()) continue
            val itemsets = descendants(control).any { localName(it) == "itemset" }
            val values = descendants(control)
                .filter { localName(it) == "item" }
                .flatMap { children(it).filter { c -> localName(c) == "value" } }
                .map { it.textContent.orEmpty() }.filter(String::isNotBlank).toSet()
            result[path] = ChoiceInfo(itemsets || values.isEmpty(), values)
        }
        return result
    }

    private fun children(element: Element): List<Element> =
        (0 until element.childNodes.length).mapNotNull { i ->
            element.childNodes.item(i).takeIf { it.nodeType == Node.ELEMENT_NODE } as? Element
        }

    private fun descendants(root: Element): Sequence<Element> = sequence {
        yield(root)
        for (child in children(root)) yieldAll(descendants(child))
    }

    private fun localName(element: Element) = element.localName ?: element.tagName.substringAfter(':')

    private fun sha256(text: String): String = MessageDigest.getInstance("SHA-256")
        .digest(text.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
