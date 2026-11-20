package org.odk.collect.android.recordwork

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Classification only; no old XML, persistent answers or transfer paths. */
class ReviewedTypedMappingClassifierTest {
    private fun field(path: String, type: PrivateScalarType,
                      vararg choices: Pair<String, String>) =
        PrivateScalarField(path, type,
            choices.map { PrivateStaticChoice(it.first, it.second) })

    private fun inspect(source: List<PrivateScalarField>,
                        target: List<PrivateScalarField>,
                        work: RecordWorkType = RecordWorkType.AMENDMENT,
                        risks: Set<String> = emptySet()) =
        ReviewedTypedMappingClassifier.inspect(work, source, target, risks)

    @Test fun exactSamePathTypedFieldsAreReviewOnlyNotPermission() {
        val original = listOf(
            field("/data/age", PrivateScalarType.INTEGER),
            field("/data/weight", PrivateScalarType.DECIMAL),
            field("/data/visited", PrivateScalarType.DATE),
            field("/data/status", PrivateScalarType.SELECT_ONE,
                "a" to "Active", "b" to "Inactive"),
            field("/data/features", PrivateScalarType.SELECT_MULTI,
                "x" to "X", "y" to "Y"))
        val result = inspect(original, original)
        assertEquals(5, result.size)
        assertTrue(result.all { it.verdict == TypedMappingVerdict.REVIEW_SAME_TYPE })
        assertTrue(result.all { !it.canTransfer && !it.canFinalize && !it.canUpload })
    }

    @Test fun typeChangesAndRenameAreBlockedRatherThanGuessed() {
        val result = inspect(listOf(
            field("/data/years", PrivateScalarType.INTEGER),
            field("/data/age", PrivateScalarType.INTEGER)
        ), listOf(
            field("/data/years", PrivateScalarType.TEXT),
            field("/data/age_renamed", PrivateScalarType.INTEGER)
        ))
        assertEquals(TypedMappingVerdict.BLOCKED_TYPE_CHANGE, result[0].verdict)
        assertEquals(TypedMappingVerdict.BLOCKED_MISSING_SOURCE, result[1].verdict)
    }

    @Test fun changedChoiceCodeCaptionOrOrderBlocksConservativeReuse() {
        val old = listOf(field("/data/status", PrivateScalarType.SELECT_ONE,
            "a" to "Active", "b" to "Inactive"))
        for (candidate in listOf(
            field("/data/status", PrivateScalarType.SELECT_ONE,
                "a" to "Active", "c" to "Inactive"),
            field("/data/status", PrivateScalarType.SELECT_ONE,
                "a" to "Alive", "b" to "Inactive"),
            field("/data/status", PrivateScalarType.SELECT_ONE,
                "b" to "Inactive", "a" to "Active"),
            field("/data/status", PrivateScalarType.SELECT_ONE,
                "a" to "Active", "a" to "Active"))) {
            assertEquals(TypedMappingVerdict.BLOCKED_CHOICE_CHANGE,
                inspect(old, listOf(candidate)).single().verdict)
        }
    }

    @Test fun riskAnnotationOverridesOtherwiseMatchingNativeType() {
        val old = listOf(field("/data/age", PrivateScalarType.INTEGER))
        assertEquals(TypedMappingVerdict.BLOCKED_RENAME_OR_SEMANTICS,
            inspect(old, old, risks = setOf("/data/age")).single().verdict)
    }

    @Test fun followUpNeverGetsAHistoricalTransferCandidate() {
        val old = listOf(field("/data/age", PrivateScalarType.INTEGER))
        val result = inspect(old, old, RecordWorkType.FOLLOW_UP).single()
        assertEquals(TypedMappingVerdict.BLOCKED_FOLLOW_UP, result.verdict)
        assertFalse(result.canTransfer)
    }

    @Test fun duplicatePathsAndUnknownRiskAnnotationsFailClosed() {
        val old = field("/data/age", PrivateScalarType.INTEGER)
        rejects { inspect(listOf(old, old), listOf(old)) }
        rejects { inspect(listOf(old), listOf(old, old)) }
        rejects { inspect(listOf(old), listOf(old), risks = setOf("/data/unknown")) }
        rejects { inspect(listOf(old), emptyList()) }
        rejects { inspect(listOf(field("/data/age[1]", PrivateScalarType.INTEGER)),
            listOf(old)) }
    }

    private fun rejects(block: () -> Unit) {
        var rejected = false
        try { block() } catch (_: IllegalArgumentException) { rejected = true }
        assertTrue("Unsupported schema must fail closed", rejected)
    }
}
