package org.odk.collect.android.recordwork

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * No Android device and no submitted user records. All XML is synthetic.
 * Test assertions intentionally verify NO field values leak to the report.
 */
class CrossVersionAssessmentTest {
    private val sourceId = "a8c07f51-b4c1-40a4-b31c-956d2ca5e048"
    private val targetVersionId = "5a6f0bf6-795c-4d91-9861-16910542b4f1"
    private val workId = "e6bddba9-e98b-49cb-8bed-dce91d400520"
    private val secret = "PRIVATE_NAME_1234_NEVER_REPORT"

    private val sourceV1 = """
        <data id="household" version="1">
          <meta><instanceID>uuid:9e1e9e90-5cc8-4e65-a5d0-acb5bdc1500d</instanceID></meta>
          <name>$secret</name>
          <old_question>deprecated value</old_question>
          <gender>removed_choice</gender>
          <photo>synthetic-image.jpg</photo>
          <visits><reason>First visit</reason></visits>
          <visits><reason>Second visit</reason></visits>
        </data>
    """.trimIndent()

    private val targetV2 = """
        <?xml version="1.0" encoding="UTF-8"?>
        <h:html xmlns:h="http://www.w3.org/1999/xhtml"
                xmlns="http://www.w3.org/2002/xforms"
                xmlns:jr="http://openrosa.org/javarosa">
          <h:head>
            <h:title>Household assessment</h:title>
            <model>
              <instance>
                <data id="household" version="2">
                  <name/><gender/><photo/><visits><reason/></visits><new_question/>
                  <meta><instanceID/><deprecatedID/></meta>
                </data>
              </instance>
              <bind nodeset="/data/name" type="string" relevant="../gender = 'female'"/>
              <bind nodeset="/data/gender" type="string"/>
              <bind nodeset="/data/photo" type="binary"/>
              <bind nodeset="/data/visits/reason" type="string"/>
              <bind nodeset="/data/new_question" type="string" calculate="1"/>
            </model>
          </h:head>
          <h:body>
            <select1 ref="/data/gender">
              <label>Gender</label>
              <item><label>Female</label><value>female</value></item>
              <item><label>Male</label><value>male</value></item>
            </select1>
            <group>
              <repeat nodeset="/data/visits">
                <input ref="/data/visits/reason"><label>Reason</label></input>
              </repeat>
            </group>
          </h:body>
        </h:html>
    """.trimIndent()

    @Test
    fun `cross version report surfaces changed paths repeats choice and media without answers`() {
        val result = assess()
        assertTrue(result.findings.any {
            it.path == "/data/old_question" &&
                MappingIssue.SOURCE_FIELD_REMOVED in it.issues
        })
        assertTrue(result.findings.any {
            it.path == "/data/new_question" &&
                MappingIssue.TARGET_FIELD_ADDED in it.issues
        })
        assertTrue(result.findings.any {
            it.path == "/data/gender" &&
                MappingIssue.CHOICE_VALUE_NOT_IN_TARGET in it.issues
        })
        assertTrue(result.findings.any {
            it.path == "/data/photo" && MappingIssue.MEDIA_REVIEW in it.issues
        })
        assertTrue(result.findings.any {
            it.path == "/data/visits/reason" &&
                MappingIssue.REPEAT_REVIEW in it.issues
        })
        assertTrue(result.findings.any {
            it.path == "/data/name" &&
                MappingIssue.RELEVANCE_OR_CONSTRAINT in it.issues
        })
        assertTrue(result.globalIssues.contains(MappingIssue.JAVAROSA_EVALUATION_REQUIRED))
        assertTrue(result.globalIssues.contains(MappingIssue.EXPLICIT_OPERATOR_APPROVAL_REQUIRED))
        assertFalse(result.canPrefill)
        assertFalse(result.canEdit)
        assertFalse(result.canSubmit)
        assertEquals("2", result.targetVersion)
        assertEquals("1", result.sourceVersion)
        val report = result.toString() + result.issueCounts().toString()
        assertFalse(report.contains(secret))
        assertFalse(report.contains("removed_choice"))
        assertFalse(report.contains("synthetic-image.jpg"))
        assertFalse(report.contains("deprecated value"))
    }

    @Test
    fun `even exact matching paths are not treated as approved migration`() {
        val source = "<data id=\"household\" version=\"2\"><name>same path</name></data>"
        val target = targetV2.replace(
            "<name/><gender/><photo/><visits><reason/></visits><new_question/>",
            "<name/>"
        )
        val result = assess(source, target)
        assertTrue(result.unchangedPathCount >= 1)
        assertFalse(result.canEdit)
        assertFalse(result.canPrefill)
        assertTrue(result.globalIssues.contains(MappingIssue.SOURCE_SCHEMA_UNAVAILABLE))
    }

    @Test
    fun `followup must be a separate observation with no implied source answer transfer`() {
        val otherForm = targetV2.replace("id=\"household\" version=\"2\"",
            "id=\"independent_visit\" version=\"2\"")
        val result = assess(
            workType = RecordWorkType.FOLLOW_UP,
            targetXForm = otherForm,
            expectedFormId = "independent_visit"
        )
        assertTrue(MappingIssue.CROSS_FORM_FOLLOW_UP in result.globalIssues)
        assertFalse(result.canPrefill)
        assertFalse(result.canSubmit)
    }

    @Test
    fun `target metadata spoof and version mismatch fail closed`() {
        reject {
            assess(expectedFormId = "malicious_other_form")
        }
        reject {
            assess(expectedVersion = "3")
        }
        reject {
            assess(targetXForm = targetV2.replace("version=\"2\"", "version=\"999\""))
        }
    }

    @Test
    fun `DOCTYPE and external entity declarations are rejected before JavaRosa processing`() {
        val evil = """<!DOCTYPE data [<!ENTITY xxe SYSTEM "file:///etc/passwd">]>
            <data id="household" version="1"><name>&xxe;</name></data>"""
        reject { assess(sourceXml = evil) }

        val evilTarget = """<!DOCTYPE html [<!ENTITY xxe SYSTEM "file:///etc/passwd">]>
            $targetV2"""
        reject { assess(targetXForm = evilTarget) }
    }

    @Test
    fun `impact report pins source and target bytes with independent SHA256 values`() {
        val before = assess()
        val alteredSource = assess(sourceXml = sourceV1.replace(secret, "a different answer"))
        val alteredTarget = assess(targetXForm = targetV2.replace("Female", "Woman"))
        assertEquals(64, before.sourceSha256.length)
        assertEquals(64, before.targetXFormSha256.length)
        assertNotEquals(before.sourceSha256, alteredSource.sourceSha256)
        assertNotEquals(before.targetXFormSha256, alteredTarget.targetXFormSha256)
        assertEquals(before.workId, alteredSource.workId)
        assertEquals(before.targetFormVersionId, alteredTarget.targetFormVersionId)
        assertFalse(alteredTarget.canEdit)
    }

    @Test
    fun `large attacker controlled XML fails before parsing or writing a draft`() {
        reject {
            assess(sourceXml = "<data>" + "a".repeat(8_388_609) + "</data>")
        }
    }

    @Test
    fun `a read-only report cannot be forged into editing authorization`() {
        val before = assess()
        try {
            before.copy(canPrefill = true, canEdit = true, canSubmit = true)
            fail("Review report must never authorize editing")
        } catch (_: IllegalArgumentException) { }
    }

    private fun assess(
        sourceXml: String = sourceV1,
        targetXForm: String = targetV2,
        expectedFormId: String = "household",
        expectedVersion: String = "2",
        workType: RecordWorkType = RecordWorkType.AMENDMENT
    ) = CrossVersionAssessmentEngine.assess(
        workId, sourceId, targetVersionId, workType,
        sourceXml, targetXForm, expectedFormId, expectedVersion
    )

    private fun reject(block: () -> Unit) {
        try {
            block()
            fail("Expected invalid or hostile form to be rejected")
        } catch (_: Exception) { }
    }
}
