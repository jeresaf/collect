package org.odk.collect.android.recordwork

import org.javarosa.model.xform.XFormsModule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Real JavaRosa static choices, synthetic work and original answers only. */
class JavaRosaPrivateStaticChoiceSessionTest {
    @get:Rule val temporary = TemporaryFolder()
    private val work = "e6bddba9-e98b-49cb-8bed-dce91d400520"
    private val source = "a8c07f51-b4c1-40a4-b31c-956d2ca5e048"
    private val target = "5a6f0bf6-795c-4d91-9861-16910542b4f1"
    private val sourceXml = """
        <data id="household" version="1">
          <color>red</color><facilities>well school</facilities>
          <meta><instanceID>uuid:9e1e9e90-5cc8-4e65-a5d0-acb5bdc1500d</instanceID></meta>
        </data>
    """.trimIndent()

    @Before fun register() { XFormsModule().registerModule() }

    private fun item(code: String, name: String) =
        "<item><label>" + name + "</label><value>" + code + "</value></item>"

    private fun form(
        version: String,
        colors: List<Pair<String, String>> = listOf("red" to "Red", "blue" to "Blue"),
        facilities: List<Pair<String, String>> = listOf(
            "well" to "Water", "school" to "School"
        ),
        extra: String = ""
    ): String {
        val colorMarkup = colors.joinToString("\n") { item(it.first, it.second) }
        val facilityMarkup = facilities.joinToString("\n") { item(it.first, it.second) }
        return """
          <h:html xmlns:h="http://www.w3.org/1999/xhtml"
                  xmlns="http://www.w3.org/2002/xforms">
            <h:head><h:title>Synthetic Choices</h:title>
              <model>
                <instance>
                  <data id="household" version="$version">
                    <color/><facilities/><meta><instanceID/></meta>
                  </data>
                </instance>
                <bind nodeset="/data/color" type="string"/>
                <bind nodeset="/data/facilities" type="string"/>
              </model>
            </h:head>
            <h:body>
              <select1 ref="/data/color"><label>Choose color</label>
                $colorMarkup
              </select1>
              <select ref="/data/facilities"><label>Choose facilities</label>
                $facilityMarkup
              </select>
              $extra
            </h:body>
          </h:html>
        """.trimIndent()
    }

    private fun session(
        targetForm: String = form(
            "2", colors = listOf("green" to "Green", "blue" to "Blue"),
            facilities = listOf(
                "well" to "Water", "school" to "School", "clinic" to "Clinic"
            )
        ),
        reviewedForm: String = targetForm,
        type: RecordWorkType = RecordWorkType.AMENDMENT
    ): JavaRosaPrivateScalarSession {
        val sourceForm = form("1")
        val plan = HistoricalSchemaMappingEngine.plan(
            work, source, target, type, sourceXml,
            if (type == RecordWorkType.AMENDMENT) sourceForm else null,
            reviewedForm, "household", "2"
        )
        val binding = QuarantinedWorkDraft(
            projectId = "project", actorContextSha256 = "a".repeat(64),
            workId = work, sourceSubmissionId = source,
            sourceInstanceId = "uuid:9e1e9e90-5cc8-4e65-a5d0-acb5bdc1500d",
            targetFormVersionId = target, targetFormId = "household",
            targetVersion = "2", workType = type,
            visitKey = if (type == RecordWorkType.FOLLOW_UP) "visit-2" else null,
            reservedInstanceId = "uuid:aaaaaaaa-aaaa-4aaa-aaaa-aaaaaaaaaaaa",
            sourceXmlSha256 = JavaRosaPrivateScalarSession.digest(sourceXml),
            sourceXFormSha256 = plan.sourceXFormSha256,
            targetXFormSha256 = plan.targetXFormSha256,
            reviewedPaths = plan.candidates.size, validationTransfers = 0,
            requiresDeprecatedId = type == RecordWorkType.AMENDMENT
        )
        return JavaRosaPrivateScalarSession(temporary.root, binding, plan, targetForm)
    }

    @Test fun exposesNativeStaticChoicesInAuthorizedTargetOrder() {
        val editor = session()
        assertEquals(listOf(PrivateScalarType.SELECT_ONE, PrivateScalarType.SELECT_MULTI),
            editor.questions.map { it.type })
        assertEquals(listOf("green", "blue"),
            editor.questions[0].choices.map { it.value })
        assertEquals(listOf("well", "school", "clinic"),
            editor.questions[1].choices.map { it.value })
        assertEquals("Green", editor.questions[0].choices.first().label)
        assertTrue(temporary.root.listFiles().orEmpty().isEmpty())
    }

    @Test fun validatesAndReopensActualNativeChoiceAnswers() {
        val saved = session().validateForPrivateSave(
            mapOf("/data/color" to "green", "/data/facilities" to "clinic well")
        )
        assertEquals("green", saved["/data/color"])
        assertEquals("well clinic", saved["/data/facilities"])
        assertEquals(saved, session().validateForPrivateSave(saved))
    }

    @Test fun changedOldChoicesCannotBeCopiedSilentlyIntoNewForm() {
        val editor = session()
        rejects { editor.validateForPrivateSave(mapOf("/data/color" to "red")) }
        rejects { editor.validateForPrivateSave(mapOf("/data/facilities" to "well unknown")) }
        assertEquals("blue", editor.validateForPrivateSave(
            mapOf("/data/color" to "blue")
        )["/data/color"])
    }

    @Test fun incompleteDraftAllowsEmptyAnswers() {
        assertEquals(mapOf("/data/color" to "", "/data/facilities" to ""),
            session().validateForPrivateSave(emptyMap()))
    }

    @Test fun duplicateUnknownAndWhitespaceDelimitedAmbiguityIsRejected() {
        for (bad in listOf("well well", "clinic  well", "clinic ", " well",
            "clinic;well", "red", "NaN", "well\nclinic", "well\tschool")) {
            rejects { session().validateForPrivateSave(
                mapOf("/data/facilities" to bad)
            ) }
        }
        rejects { session().validateForPrivateSave(
            mapOf("/data/color" to "green blue")
        ) }
        rejects { session().validateForPrivateSave(
            mapOf("/data/foreign" to "green")
        ) }
    }

    @Test fun dynamicItemsRepeatsMediaAndDuplicateCodesAreRejected() {
        rejects { session(targetForm = form("2", extra = """
            <select1 ref="/data/color"><label>Dynamic</label>
              <itemset nodeset="instance('remote')/items/item">
                <label ref="label"/><value ref="code"/>
              </itemset>
            </select1>
        """.trimIndent())) }
        rejects { session(targetForm = form("2", extra = """
            <repeat nodeset="/data/member"><input ref="name">
              <label>Name</label></input></repeat>
        """.trimIndent())) }
        rejects { session(targetForm = form("2", extra = """
            <upload ref="/data/image" mediatype="image/*"><label>Image</label></upload>
        """.trimIndent())) }
        rejects { session(targetForm = form("2", colors =
            listOf("green" to "Green", "green" to "Duplicate"))) }
        rejects { session(targetForm = form("2", colors =
            listOf("bad value" to "Illegal"))) }
    }

    @Test fun schemaDriftAndIndependentVisitDoNotReuseOldValues() {
        val signed = form("2")
        rejects { session(
            targetForm = signed.replace("Blue", "Changed caption"),
            reviewedForm = signed
        ) }
        val followup = session(type = RecordWorkType.FOLLOW_UP)
        val blank = followup.validateForPrivateSave(emptyMap())
        assertEquals("", blank["/data/color"])
        assertEquals("", blank["/data/facilities"])
    }

    private fun rejects(block: () -> Unit) {
        var denied = false
        try { block() } catch (_: Exception) { denied = true }
        assertTrue("Untrusted native choice values must be rejected", denied)
    }
}
