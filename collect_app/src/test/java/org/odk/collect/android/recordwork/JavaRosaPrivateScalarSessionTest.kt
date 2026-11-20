package org.odk.collect.android.recordwork

import org.javarosa.model.xform.XFormsModule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Uses real JavaRosa form definitions and answer classes, never XML copying
 * or a mocked parser. All source/target forms and answers are synthetic.
 */
class JavaRosaPrivateScalarSessionTest {
    @get:Rule val scratch = TemporaryFolder()

    private val work = "e6bddba9-e98b-49cb-8bed-dce91d400520"
    private val sourceId = "a8c07f51-b4c1-40a4-b31c-956d2ca5e048"
    private val targetId = "5a6f0bf6-795c-4d91-9861-16910542b4f1"
    private val old = """
        <data id="household" version="1">
          <name>OLD_PRIVATE_DATA</name>
          <age>30</age>
          <weight>62.5</weight>
          <visitDate>2025-09-18</visitDate>
          <meta><instanceID>uuid:9e1e9e90-5cc8-4e65-a5d0-acb5bdc1500d</instanceID></meta>
        </data>
    """.trimIndent()

    @Before fun register() {
        XFormsModule().registerModule()
    }

    private fun form(
        version: String,
        ageType: String,
        dateType: String = "date",
        extra: String = ""
    ) = """
        <h:html xmlns:h="http://www.w3.org/1999/xhtml"
                xmlns="http://www.w3.org/2002/xforms">
          <h:head><h:title>Household</h:title>
            <model>
              <instance>
                <data id="household" version="$version">
                  <name/><age/><weight/><visitDate/>
                  <meta><instanceID/></meta>
                </data>
              </instance>
              <bind nodeset="/data/name" type="string"/>
              <bind nodeset="/data/age" type="$ageType"/>
              <bind nodeset="/data/weight" type="decimal"/>
              <bind nodeset="/data/visitDate" type="$dateType"/>
            </model>
          </h:head>
          <h:body>
            <input ref="/data/name"><label>Name</label></input>
            <input ref="/data/age"><label>Age</label></input>
            <input ref="/data/weight"><label>Weight</label></input>
            <input ref="/data/visitDate"><label>Visit date</label></input>
            $extra
          </h:body>
        </h:html>
    """.trimIndent()

    private fun session(
        targetXml: String = form("2", "int"),
        reviewedXml: String = targetXml
    ): JavaRosaPrivateScalarSession {
        val sourceXForm = form("1", "string")
        val plan = HistoricalSchemaMappingEngine.plan(
            work, sourceId, targetId, RecordWorkType.AMENDMENT, old,
            sourceXForm, reviewedXml, "household", "2"
        )
        val binding = QuarantinedWorkDraft(
            projectId = "project", actorContextSha256 = "a".repeat(64),
            workId = work, sourceSubmissionId = sourceId,
            sourceInstanceId = "uuid:9e1e9e90-5cc8-4e65-a5d0-acb5bdc1500d",
            targetFormVersionId = targetId,
            targetFormId = "household", targetVersion = "2",
            workType = RecordWorkType.AMENDMENT, visitKey = null,
            reservedInstanceId = "uuid:aaaaaaaa-aaaa-4aaa-aaaa-aaaaaaaaaaaa",
            sourceXmlSha256 = JavaRosaPrivateScalarSession.digest(old),
            sourceXFormSha256 = plan.sourceXFormSha256,
            targetXFormSha256 = plan.targetXFormSha256,
            reviewedPaths = plan.candidates.size,
            validationTransfers = 0, requiresDeprecatedId = true
        )
        return JavaRosaPrivateScalarSession(
            scratch.root, binding, plan, targetXml
        )
    }

    private val answers = linkedMapOf(
        "/data/name" to "Second authorized visit",
        "/data/age" to "42",
        "/data/weight" to "72.5",
        "/data/visitDate" to "2026-11-20"
    )

    @Test fun realNativeTypesValidateAndRecoverTypedAnswers() {
        val editor = session()
        assertEquals(
            listOf(
                PrivateScalarType.TEXT, PrivateScalarType.INTEGER,
                PrivateScalarType.DECIMAL, PrivateScalarType.DATE
            ),
            editor.questions.map { it.type }
        )
        assertEquals(answers, editor.validateForPrivateSave(answers))
        assertEquals(answers, session().validateForPrivateSave(answers))
        assertTrue(scratch.root.listFiles().orEmpty().isEmpty())
    }

    @Test fun manuallyReenteredTargetTypeChangesAreSupportedWithoutOldValueCopy() {
        // Original age was string; new version uses integer. This is an
        // explicitly reentered value, never a transfer from the old instance.
        val edited = session()
        assertEquals(PrivateScalarType.INTEGER, edited.questions[1].type)
        val data = edited.validateForPrivateSave(mapOf("/data/age" to "-12"))
        assertEquals("-12", data["/data/age"])
        assertEquals("", data["/data/name"])
        assertTrue(scratch.root.listFiles().orEmpty().isEmpty())
    }

    @Test fun numericValuesAreCanonicalizedOnlyAfterNativeEvaluation() {
        val modified = answers.toMutableMap().apply {
            this["/data/age"] = "-0"
            this["/data/weight"] = "004.5000"
        }
        // Strict rule blocks leading zeros; no arbitrary silent coercion.
        rejects { session().validateForPrivateSave(modified) }
        modified["/data/weight"] = "4.5000"
        val result = session().validateForPrivateSave(modified)
        assertEquals("0", result["/data/age"])
        assertEquals("4.5", result["/data/weight"])
    }

    @Test fun javaRosaIntegerIsSigned32BitAndRejectsAmbiguousInput() {
        for (bad in listOf("2147483648", "-2147483649", "03", "1.0", "1e3",
            "1,000", " 20", "+3", "NaN")) {
            rejects { session().validateForPrivateSave(
                mapOf("/data/age" to bad)
            ) }
        }
        assertEquals("2147483647", session().validateForPrivateSave(
            mapOf("/data/age" to "2147483647")
        )["/data/age"])
    }

    @Test fun decimalRejectsExponentNonfiniteAndExcessivePrecision() {
        for (bad in listOf("NaN", "Infinity", "1e12", "3,2",
            "1.12345678901234567", "1000000000001", "3.4.5", " 3.1")) {
            rejects { session().validateForPrivateSave(
                mapOf("/data/weight" to bad)
            ) }
        }
        assertEquals("-0.5", session().validateForPrivateSave(
            mapOf("/data/weight" to "-0.5000")
        )["/data/weight"])
    }

    @Test fun datesMustBeStrictIsoValidGregorianDays() {
        for (bad in listOf("2026-02-29", "2026-13-01", "2026-11-31",
            "20/11/2026", "2026-2-1", "1899-12-31", "2101-01-01")) {
            rejects { session().validateForPrivateSave(
                mapOf("/data/visitDate" to bad)
            ) }
        }
        assertEquals("2028-02-29", session().validateForPrivateSave(
            mapOf("/data/visitDate" to "2028-02-29")
        )["/data/visitDate"])
    }

    @Test fun unfinishedDraftCanLeaveAllTypedAnswersBlank() {
        val result = session().validateForPrivateSave(emptyMap())
        assertEquals(answers.keys, result.keys)
        assertTrue(result.values.all { it.isEmpty() })
        assertTrue(scratch.root.listFiles().orEmpty().isEmpty())
    }

    @Test fun newNativeFormTypeCannotBeHiddenByOldBinding() {
        rejects {
            session(
                targetXml = form("2", "decimal"),
                reviewedXml = form("2", "int")
            )
        }
        rejects {
            session(targetXml = form("2", "int", dateType = "dateTime"))
        }
    }

    @Test fun unsupportedControlsAndConditionsStillFailClosed() {
        rejects { session(targetXml = form("2", "int", extra = """
            <select1 ref="/data/age">
              <item><label>One</label><value>1</value></item>
            </select1>
        """.trimIndent())) }
        rejects { session(targetXml = form("2", "int", extra = """
            <repeat nodeset="/data/householdMembers">
              <input ref="name"><label>Name</label></input>
            </repeat>
        """.trimIndent())) }
        rejects { session(targetXml = form("2", "int").replace(
            """type="int"/>""",
            """type="int" relevant="/data/name = 'yes'"/>"""
        )) }
        rejects { session(targetXml = form("2", "int").replace(
            "</instance>",
            """</instance><instance id="external" src="https://example.test/x.xml"/>"""
        )) }
        assertTrue(scratch.root.listFiles().orEmpty().isEmpty())
    }

    private fun rejects(block: () -> Unit) {
        var rejected = false
        try { block() } catch (_: Exception) { rejected = true }
        assertTrue("Invalid native scalar draft must be blocked", rejected)
    }
}
