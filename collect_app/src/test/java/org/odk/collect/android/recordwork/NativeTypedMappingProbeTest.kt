package org.odk.collect.android.recordwork

import org.javarosa.model.xform.XFormsModule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Synthetic XForms only; never reads or stores actual household answers. */
class NativeTypedMappingProbeTest {
    @get:Rule val disk = TemporaryFolder()
    @Before fun register() { XFormsModule().registerModule() }

    private fun form(version: String, kind: String = "int",
                     answer: String = "age") = """
        <h:html xmlns:h="http://www.w3.org/1999/xhtml"
                xmlns="http://www.w3.org/2002/xforms">
          <h:head><h:title>Test</h:title><model><instance>
            <data id="household" version="$version"><$answer/><meta><instanceID/></meta></data>
          </instance><bind nodeset="/data/$answer" type="$kind"/></model></h:head>
          <h:body><input ref="/data/$answer"><label>Question</label></input></h:body>
        </h:html>
    """.trimIndent()

    private fun plan(source: String, target: String, work: RecordWorkType =
        RecordWorkType.AMENDMENT): HistoricalMappingPlan =
        HistoricalSchemaMappingEngine.plan(
            "ec71b8e1-635a-4e35-8e6e-fe6b9bb1780e",
            "91615566-c3d8-416a-afc1-2d3cf42fc199",
            "4d6ce72d-600e-4d4e-b314-6916613b6d0a",
            work,
            """<data id="household" version="1"><age>22</age></data>""",
            source, target, "household", "2"
        )

    private fun probe(source: String, target: String) =
        NativeTypedMappingProbe(disk.root).inspect(plan(source, target), source, target)

    @Test fun sameNativeIntegerIsAdvisoryOnly() {
        val result = probe(form("1"), form("2")).single()
        assertEquals(TypedMappingVerdict.REVIEW_SAME_TYPE, result.verdict)
        assertFalse(result.canTransfer)
        assertFalse(result.canFinalize)
        assertFalse(result.canUpload)
        assertTrue(disk.root.listFiles().orEmpty().isEmpty())
    }

    @Test fun nativeTypeChangeIsBlocked() {
        assertEquals(TypedMappingVerdict.BLOCKED_TYPE_CHANGE,
            probe(form("1"), form("2", "decimal")).single().verdict)
    }

    @Test fun renamedHistoricalQuestionIsNotGuessed() {
        assertEquals(TypedMappingVerdict.BLOCKED_MISSING_SOURCE,
            probe(form("1"), form("2", answer = "years")).single().verdict)
    }

    @Test fun differentSourceHashIsDeniedBeforeNativeParsing() {
        val old = form("1")
        val target = form("2")
        rejects {
            NativeTypedMappingProbe(disk.root).inspect(
                plan(old, target), old.replace("Test", "Changed"), target)
        }
    }

    @Test fun unsafeCalculatedBindFailsClosed() {
        val old = form("1")
        val target = form("2").replace(
            """type="int" """.trim(), """type="int" calculate="2+2" """.trim())
        rejects { probe(old, target) }
    }

    @Test fun followUpIsNeverAnHistoricalMigration() {
        val old = form("1")
        val target = form("2")
        rejects {
            NativeTypedMappingProbe(disk.root).inspect(
                plan(old, target, RecordWorkType.FOLLOW_UP), old, target)
        }
    }

    private fun rejects(block: () -> Unit) {
        var rejected = false
        try { block() } catch (_: IllegalArgumentException) { rejected = true }
        assertTrue("Unsafe migration schema must fail closed", rejected)
    }
}
