package org.odk.collect.android.recordwork

/**
 * Advisory-only compatibility classification of TWO independently JavaRosa-
 * verified, exact-version scalar inventories. This is NOT an approval,
 * conversion, historical-answer reader, or submission permission.
 *
 * The caller must obtain source and target inventories from the native
 * JavaRosa forms tied to freshly authorized READY work. Never feed an
 * unverified JSON, label-based guess, or stale stored schema into this API.
 */
enum class TypedMappingVerdict {
    REVIEW_SAME_TYPE,
    BLOCKED_MISSING_SOURCE,
    BLOCKED_TYPE_CHANGE,
    BLOCKED_CHOICE_CHANGE,
    BLOCKED_RENAME_OR_SEMANTICS,
    BLOCKED_FOLLOW_UP
}

data class TypedMappingFinding(
    val targetPath: String,
    val nativeType: PrivateScalarType,
    val verdict: TypedMappingVerdict,
    val reason: String,
    val canTransfer: Boolean = false,
    val canFinalize: Boolean = false,
    val canUpload: Boolean = false
) {
    init {
        require(!canTransfer && !canFinalize && !canUpload) {
            "Advisory schema comparison cannot authorize transfer or submission"
        }
    }
}

object ReviewedTypedMappingClassifier {
    /**
     * An exact same-path, same-type comparison is only a CANDIDATE for later
     * operator review. Even REVIEW_SAME_TYPE requires independent semantic,
     * constraint, relevance, value, source/target authorization and durable
     * mapping-rule checks before any historical answer can be accessed.
     */
    fun inspect(
        workType: RecordWorkType,
        sourceFields: List<PrivateScalarField>,
        targetFields: List<PrivateScalarField>,
        changedMeaningOrRules: Set<String> = emptySet()
    ): List<TypedMappingFinding> {
        require(targetFields.isNotEmpty() && targetFields.size <= 30 &&
            sourceFields.size <= 30) { "Unbounded or missing native field inventory" }
        require((sourceFields + targetFields).all {
            it.path.matches(Regex("/[A-Za-z_][A-Za-z0-9_.-]*(/[A-Za-z_][A-Za-z0-9_.-]*){1,11}"))
        } && sourceFields.map { it.path }.toSet().size == sourceFields.size &&
            targetFields.map { it.path }.toSet().size == targetFields.size) {
            "Ambiguous or unsafe native field inventory"
        }
        require(changedMeaningOrRules.all { path ->
            targetFields.any { it.path == path }
        }) { "Unrecognized semantic-risk path" }
        val old = sourceFields.associateBy { it.path }
        return targetFields.map { target ->
            val source = old[target.path]
            val (verdict, why) = when {
                workType != RecordWorkType.AMENDMENT ->
                    TypedMappingVerdict.BLOCKED_FOLLOW_UP to "Independent visit never copies historical answers"
                target.path in changedMeaningOrRules ->
                    TypedMappingVerdict.BLOCKED_RENAME_OR_SEMANTICS to "Semantic or rule change requires separate authorization"
                source == null ->
                    TypedMappingVerdict.BLOCKED_MISSING_SOURCE to "No exact original path; renamed paths are not guessed"
                source.type != target.type ->
                    TypedMappingVerdict.BLOCKED_TYPE_CHANGE to "Native source and target types differ"
                source.type in setOf(PrivateScalarType.SELECT_ONE, PrivateScalarType.SELECT_MULTI) &&
                    !sameChoices(source, target) ->
                    TypedMappingVerdict.BLOCKED_CHOICE_CHANGE to "Static choice codes or meanings differ"
                else ->
                    TypedMappingVerdict.REVIEW_SAME_TYPE to "Native same-type candidate only; not transfer authorization"
            }
            TypedMappingFinding(target.path, target.type, verdict, why)
        }
    }

    private fun sameChoices(a: PrivateScalarField, b: PrivateScalarField): Boolean {
        // Exact ordered code+caption identity is deliberately conservative:
        // label/meaning changes cannot silently reuse an old response.
        if (a.choices.isEmpty() || b.choices.isEmpty() ||
            a.choices.size > 40 || b.choices.size > 40) return false
        if (a.choices.map { it.value }.toSet().size != a.choices.size ||
            b.choices.map { it.value }.toSet().size != b.choices.size) return false
        return a.choices == b.choices
    }
}
