package org.odk.collect.android.recordwork

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoricalSchemaMappingTest {
    private val sourceId = "a8c07f51-b4c1-40a4-b31c-956d2ca5e048"
    private val targetId = "5a6f0bf6-795c-4d91-9861-16910542b4f1"
    private val workId = "e6bddba9-e98b-49cb-8bed-dce91d400520"
    private val confidential = "PRIVATE_HOUSEHOLD_NAME_NEVER_DISPLAY"
    private val submitted = """
        <data id="household" version="1">
          <meta><instanceID>uuid:9e1e9e90-5cc8-4e65-a5d0-acb5bdc1500d</instanceID></meta>
          <name>$confidential</name><age>27</age><choice>a</choice>
          <members><person>one</person></members>
          <members><person>two</person></members>
          <former>old</former>
        </data>
    """.trimIndent()

    private fun xform(version: String, rootId: String = "household", fields: String,
                      binds: String, body: String = "") = """
        <h:html xmlns:h="http://www.w3.org/1999/xhtml"
                xmlns="http://www.w3.org/2002/xforms">
          <h:head><h:title>Synthetic mapping form</h:title>
          <model><instance>
            <data id="$rootId" version="$version">
              $fields
              <meta><instanceID/></meta>
            </data>
          </instance>$binds</model></h:head>
          <h:body>$body</h:body>
        </h:html>
    """.trimIndent()

    private val sourceSchema = xform("1",
        fields = "<name/><age/><choice/><members><person/></members><former/>",
        binds = """
          <bind nodeset="/data/name" type="string"/>
          <bind nodeset="/data/age" type="int"/>
          <bind nodeset="/data/choice" type="string"/>
          <bind nodeset="/data/members/person" type="string"/>
          <bind nodeset="/data/former" type="string"/>
        """.trimIndent(),
        body = """
          <select1 ref="/data/choice"><item><label>A</label><value>a</value></item></select1>
          <repeat nodeset="/data/members">
            <input ref="/data/members/person"><label>Person</label></input>
          </repeat>
        """.trimIndent()
    )
    private val newerSchema = xform("2",
        fields = "<name/><age/><choice/><members><person/></members><newField/><photo/>",
        binds = """
          <bind nodeset="/data/name" type="string"/>
          <bind nodeset="/data/age" type="decimal"/>
          <bind nodeset="/data/choice" type="string"/>
          <bind nodeset="/data/members/person" type="string"/>
          <bind nodeset="/data/newField" type="string" calculate="5"/>
          <bind nodeset="/data/photo" type="binary"/>
        """.trimIndent(),
        body = """
          <select1 ref="/data/choice"><item><label>B</label><value>b</value></item></select1>
          <repeat nodeset="/data/members">
            <input ref="/data/members/person"><label>Person</label></input>
          </repeat>
          <upload ref="/data/photo"><label>Photo</label></upload>
        """.trimIndent()
    )

    @Test fun unchangedScalarPathIsOnlySimpleCandidate() {
        val plan = plan()
        assertEquals("1", plan.sourceVersion)
        assertEquals("2", plan.targetVersion)
        assertEquals("household", plan.sourceFormId)
        assertTrue(plan.removedSourcePaths.contains("/data/former"))
        assertTrue(plan.candidates.single { it.targetPath == "/data/name" }.safeScalarProposal)
        assertFalse(plan.candidates.single { it.targetPath == "/data/age" }.safeScalarProposal)
        assertTrue(MappingRisk.TYPE_CHANGED in plan.candidates.single { it.targetPath == "/data/age" }.risks)
        assertTrue(MappingRisk.CHOICE_LIST_CHANGED in plan.candidates.single { it.targetPath == "/data/choice" }.risks)
        assertTrue(MappingRisk.REPEAT in plan.candidates.single { it.targetPath == "/data/members/person" }.risks)
        assertTrue(MappingRisk.CALCULATION in plan.candidates.single { it.targetPath == "/data/newField" }.risks)
        assertTrue(MappingRisk.MEDIA in plan.candidates.single { it.targetPath == "/data/photo" }.risks)
        assertFalse(plan.canPrefill)
        assertFalse(plan.canEdit)
        assertFalse(plan.canSubmit)
        assertFalse(plan.toString().contains(confidential))
        assertFalse(plan.toString().contains("one"))
    }

    @Test fun humanReviewCannotGrantSubmission() {
        val plan = plan()
        val review = HistoricalMappingReview(plan)
        assertFalse(review.snapshot().reviewedEveryTargetPath)
        review.decide("/data/name", MappingDecision.REQUEST_REVIEWED_TRANSFER)
        for (field in plan.candidates.filter { it.targetPath != "/data/name" }) {
            review.decide(field.targetPath, MappingDecision.REENTER)
        }
        val result = review.snapshot()
        assertTrue(result.reviewedEveryTargetPath)
        assertEquals(MappingDecision.REQUEST_REVIEWED_TRANSFER, result.decisions["/data/name"])
        assertTrue(result.allTransfersNeedJavaRosa)
        assertFalse(result.canSubmit)
        assertEquals(plan.targetXFormSha256, result.targetXFormSha256)
        reject { review.decide("/data/age", MappingDecision.REQUEST_REVIEWED_TRANSFER) }
        reject { review.decide("/data/members/person", MappingDecision.REQUEST_REVIEWED_TRANSFER) }
        reject { review.decide("/data/other", MappingDecision.REENTER) }
        reject { result.copy(canSubmit = true) }
        reject { plan.copy(canEdit = true) }
    }

    @Test fun missingHistoricalSchemaIsNotReplacedByLatest() {
        val missing = plan(originalXForm = null)
        assertNull(missing.sourceXFormSha256)
        assertTrue(MappingRisk.NO_HISTORICAL_SCHEMA in missing.globalRisks)
        assertTrue(missing.candidates.none { it.safeScalarProposal })
        reject { HistoricalMappingReview(missing).decide(
            "/data/name", MappingDecision.REQUEST_REVIEWED_TRANSFER
        ) }
    }

    @Test fun sourceSchemaMustMatchSubmittedVersion() {
        val wrongVersion = plan(originalXForm = sourceSchema.replace("version=\"1\"", "version=\"3\""))
        assertTrue(MappingRisk.SOURCE_SCHEMA_MISMATCH in wrongVersion.globalRisks)
        assertTrue(wrongVersion.candidates.none { it.safeScalarProposal })
        val wrongId = plan(originalXForm = sourceSchema.replace("id=\"household\"", "id=\"other\""))
        assertTrue(MappingRisk.SOURCE_SCHEMA_MISMATCH in wrongId.globalRisks)
        assertFalse(wrongId.canEdit)
    }

    @Test fun followupsStayIndependentEvenWithMatchingFields() {
        val visit = plan(type = RecordWorkType.FOLLOW_UP)
        assertTrue(MappingRisk.DIFFERENT_FORM_FOLLOW_UP in visit.globalRisks)
        assertTrue(visit.candidates.none { it.safeScalarProposal })
        assertFalse(visit.canPrefill)
        assertFalse(visit.canSubmit)
    }

    @Test fun hashesBindToExactSourceAndTargetSchemas() {
        val a = plan()
        val changed = plan(targetXForm = newerSchema.replace("decimal", "int"))
        assertEquals(64, requireNotNull(a.sourceXFormSha256).length)
        assertEquals(64, a.targetXFormSha256.length)
        assertNotEquals(a.targetXFormSha256, changed.targetXFormSha256)
        assertTrue(a.sourceXFormSha256 != a.targetXFormSha256)
        assertFalse(changed.canSubmit)
    }

    @Test fun relativeFormReferencesFailClosed() {
        reject { plan(targetXForm = newerSchema.replace(
            "nodeset=\"/data/members\"", "nodeset=\"members\""
        )) }
        reject { plan(targetXForm = newerSchema.replace(
            "nodeset=\"/data/name\"", "nodeset=\"name\""
        )) }
    }

    @Test fun reviewDoesNotExposeAnswers() {
        val result = plan()
        val rendered = result.toString()
        assertFalse(rendered.contains(confidential))
        assertFalse(rendered.contains("uuid:9e1e9e90"))
        assertFalse(rendered.contains("<data"))
        assertTrue(result.candidates.all { it.targetPath.startsWith("/data/") })
    }

    @Test fun duplicateBindCannotHideOriginalConstraint() {
        val changed = newerSchema.replace(
            "<bind nodeset=\"/data/name\" type=\"string\"/>",
            "<bind nodeset=\"/data/name\" type=\"string\" constraint=\"false()\"/>" +
                "<bind nodeset=\"/data/name\" type=\"string\"/>"
        )
        reject { plan(targetXForm = changed) }
    }

    @Test fun ancestorRulesAndConditionalReadonlyPreventScalarProposal() {
        val changed = newerSchema.replace(
            "<bind nodeset=\"/data/name\" type=\"string\"/>",
            "<bind nodeset=\"/data\" relevant=\"false()\" required=\"/data/age &gt; 0\"/>" +
                "<bind nodeset=\"/data/name\" type=\"string\" readonly=\"/data/age &gt; 0\"/>"
        )
        val candidate = plan(targetXForm = changed).candidates.single { it.targetPath == "/data/name" }
        assertFalse(candidate.safeScalarProposal)
        assertTrue(MappingRisk.RELEVANCE_OR_CONSTRAINT in candidate.risks)
        assertTrue(MappingRisk.REQUIRED in candidate.risks)
        assertTrue(MappingRisk.CALCULATION in candidate.risks)
    }

    @Test fun changedChoiceCaptionWithSameCodeRequiresReview() {
        val changed = newerSchema.replace("<value>b</value>", "<value>a</value>")
        val candidate = plan(targetXForm = changed).candidates.single { it.targetPath == "/data/choice" }
        assertTrue(MappingRisk.CHOICE_LIST_CHANGED in candidate.risks)
        assertFalse(candidate.safeScalarProposal)
    }

    @Test fun duplicateChoiceCodeAndPredicateReferencesFailClosed() {
        val duplicate = newerSchema.replace("<value>b</value></item>",
            "<value>b</value></item><item><label>Different</label><value>b</value></item>")
        reject { plan(targetXForm = duplicate) }
        reject { plan(targetXForm = newerSchema.replace(
            "nodeset=\"/data/name\"", "nodeset=\"/data/name[1]\""
        )) }
    }

    @Test fun hostileSourceDefinitionIsRejected() {
        reject { plan(originalXForm = "<!DOCTYPE html [<!ENTITY xxe SYSTEM \"file:///etc/passwd\">]>"
            + sourceSchema) }
    }

    private fun plan(originalXForm: String? = sourceSchema, targetXForm: String = newerSchema,
                     type: RecordWorkType = RecordWorkType.AMENDMENT) =
        HistoricalSchemaMappingEngine.plan(
            workId, sourceId, targetId, type, submitted,
            originalXForm, targetXForm, "household", "2"
        )

    private fun reject(block: () -> Unit) {
        var rejected = false
        try {
            block()
        } catch (_: IllegalArgumentException) {
            rejected = true
        } catch (_: org.xml.sax.SAXException) {
            rejected = true
        }
        assertTrue("An unreviewed/malicious mapping must be blocked", rejected)
    }
}
