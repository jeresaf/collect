package org.odk.collect.android.recordwork

import java.security.MessageDigest
import java.util.UUID

/** Wire-compatible advisory manifest; no answer values or operational rights. */
enum class ExplicitScalarType { TEXT, INTEGER, DECIMAL, DATE }
enum class ExplicitRuleVerdict {
    BLOCKED_UNKNOWN_PATH, BLOCKED_RENAME_COLLISION, BLOCKED_TYPE_OR_UNSUPPORTED,
    BLOCKED_COMPLEX_RULES, REQUIRES_NATIVE_AND_SEMANTIC_REVIEW
}

data class ExplicitFieldRule(val sourcePath: String, val targetPath: String, val type: ExplicitScalarType) {
    init {
        require(validPath(sourcePath) && validPath(targetPath)) { "Invalid mapping path" }
    }
    companion object {
        private val PATH = Regex("/[A-Za-z_][A-Za-z0-9_.-]*(/[A-Za-z_][A-Za-z0-9_.-]*){1,11}")
        private fun validPath(path: String): Boolean =
            path.length <= 255 && PATH.matches(path) &&
                path.split('/').none { it.lowercase(java.util.Locale.ROOT) in
                    setOf("meta", "instanceid", "deprecatedid", "deviceid") }
    }
}

/** Canonical V1 is ASCII, newline framed, target-path ordered and one-to-one. */
class ExplicitMappingManifest(
    val workId: String, val sourceSubmissionId: String, val targetFormVersionId: String,
    val sourceXFormSha256: String, val targetXFormSha256: String,
    rules: List<ExplicitFieldRule>
) {
    val rules: List<ExplicitFieldRule> = java.util.Collections.unmodifiableList(
        rules.also { require(it.size in 1..30) }.sortedBy { it.targetPath }
    )
    init {
        require(listOf(workId, sourceSubmissionId, targetFormVersionId).all {
            UUID.fromString(it).toString() == it && it != "00000000-0000-0000-0000-000000000000"
        })
        require(listOf(sourceXFormSha256, targetXFormSha256).all { it.matches(Regex("[0-9a-f]{64}")) })
        require(this.rules.size in 1..30 &&
            this.rules.map { it.sourcePath }.toSet().size == this.rules.size &&
            this.rules.map { it.targetPath }.toSet().size == this.rules.size) {
            "Mapping rules must be bounded and one-to-one"
        }
    }
    fun canonicalBytes(): ByteArray = buildString {
        append("DATA_COLLECTOR_EXPLICIT_MAPPING_V1\n")
        listOf(workId, sourceSubmissionId, targetFormVersionId, sourceXFormSha256,
            targetXFormSha256, rules.size.toString()).forEach { append(it).append('\n') }
        rules.forEach { append(it.sourcePath).append('\t').append(it.targetPath)
            .append('\t').append(it.type.name).append('\n') }
    }.toByteArray(Charsets.US_ASCII)
    fun sha256(): String = MessageDigest.getInstance("SHA-256").digest(canonicalBytes())
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }
}

data class ExplicitRuleFinding(val rule: ExplicitFieldRule, val verdict: ExplicitRuleVerdict)
data class ExplicitMappingReview(val manifest: ExplicitMappingManifest,
    val findings: List<ExplicitRuleFinding>) {
    val canReadHistoricalAnswers: Boolean get() = false
    val canTransfer: Boolean get() = false
    val canFinalize: Boolean get() = false
    val canUpload: Boolean get() = false
}
