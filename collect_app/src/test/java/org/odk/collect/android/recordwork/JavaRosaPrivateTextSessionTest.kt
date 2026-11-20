package org.odk.collect.android.recordwork

import org.javarosa.model.xform.XFormsModule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class JavaRosaPrivateTextSessionTest {
    @get:Rule val scratch = TemporaryFolder()

    private val work = "e6bddba9-e98b-49cb-8bed-dce91d400520"
    private val source = "a8c07f51-b4c1-40a4-b31c-956d2ca5e048"
    private val target = "5a6f0bf6-795c-4d91-9861-16910542b4f1"
    private val originalSource = """
        <data id="household" version="1"><name>OLDER_PRIVATE_VALUE</name>
        <meta><instanceID>uuid:9e1e9e90-5cc8-4e65-a5d0-acb5bdc1500d</instanceID></meta></data>
    """.trimIndent()

    @Before fun register() {
        XFormsModule().registerModule()
    }

    private fun xform(
        version: String,
        fields: String = "<name/><newNote/>",
        bindType: String = "string",
        extraBody: String = ""
    ) = """
        <h:html xmlns:h="http://www.w3.org/1999/xhtml"
                xmlns="http://www.w3.org/2002/xforms">
          <h:head><h:title>Synthetic Household</h:title>
            <model><instance>
                <data id="household" version="$version">
                  $fields<meta><instanceID/></meta>
                </data>
              </instance>
              <bind nodeset="/data/name" type="$bindType"/>
              <bind nodeset="/data/newNote" type="string"/>
            </model></h:head>
          <h:body>
            <input ref="/data/name"><label>Name</label></input>
            <input ref="/data/newNote"><label>Note</label></input>
            $extraBody
          </h:body>
        </h:html>
    """.trimIndent()

    private fun session(
        form: String = xform("2"),
        boundTo: String = form
    ): JavaRosaPrivateTextSession {
        val original = xform("1", fields = "<name/>")
            .replace("""<bind nodeset="/data/newNote" type="string"/>""", "")
            .replace("""<input ref="/data/newNote"><label>Note</label></input>""", "")
        val plan = HistoricalSchemaMappingEngine.plan(
            work, source, target, RecordWorkType.AMENDMENT,
            originalSource, original, boundTo, "household", "2"
        )
        val binding = QuarantinedWorkDraft(
            projectId = "local-odk-project", actorContextSha256 = "a".repeat(64),
            workId = work, sourceSubmissionId = source,
            sourceInstanceId = "uuid:9e1e9e90-5cc8-4e65-a5d0-acb5bdc1500d",
            targetFormVersionId = target, targetFormId = "household",
            targetVersion = "2", workType = RecordWorkType.AMENDMENT,
            visitKey = null,
            reservedInstanceId = "uuid:aaaaaaaa-aaaa-4aaa-aaaa-aaaaaaaaaaaa",
            sourceXmlSha256 = JavaRosaPrivateTextSession.sha256(originalSource),
            sourceXFormSha256 = plan.sourceXFormSha256,
            targetXFormSha256 = plan.targetXFormSha256,
            reviewedPaths = plan.candidates.size, validationTransfers = 0,
            requiresDeprecatedId = true
        )
        return JavaRosaPrivateTextSession(scratch.root, binding, plan, form)
    }

    @Test fun nativeTextEntriesCanBeValidatedAndRevalidatedAfterRestart() {
        val editor = session()
        assertEquals(listOf("/data/name", "/data/newNote"), editor.fields)
        val values = mapOf(
            "/data/name" to "Edited record answer",
            "/data/newNote" to "Fresh version-two answer"
        )
        val clean = editor.validateForPrivateSave(values)
        assertEquals(values, clean)
        assertEquals(values, session().validateForPrivateSave(clean))
        assertTrue(scratch.root.listFiles().orEmpty().isEmpty())
    }

    @Test fun incompleteDraftAllowsBlankFieldsButNeverFinalizes() {
        val editor = session()
        assertEquals(
            mapOf("/data/name" to "", "/data/newNote" to ""),
            editor.validateForPrivateSave(emptyMap())
        )
        assertTrue(scratch.root.listFiles().orEmpty().isEmpty())
    }

    @Test fun refusesUnreviewedAnswerPaths() {
        val editor = session()
        reject {
            editor.validateForPrivateSave(mapOf("/data/injected" to "hidden answer"))
        }
        assertTrue(scratch.root.listFiles().orEmpty().isEmpty())
    }

    @Test fun refusesExcessiveTextWithoutWritingAnyInstance() {
        val editor = session()
        reject {
            editor.validateForPrivateSave(mapOf("/data/name" to
                "A".repeat(JavaRosaPrivateTextSession.MAX_TEXT + 1)))
        }
        assertTrue(scratch.root.listFiles().orEmpty().isEmpty())
    }

    @Test fun exactTargetSchemaHashIsMandatory() {
        reject { session(
            form = xform("2").replace("Synthetic Household", "Unapproved Label"),
            boundTo = xform("2")
        ) }
        assertTrue(scratch.root.listFiles().orEmpty().isEmpty())
    }

    @Test fun rejectsNonTextNativeTypes() {
        reject { session(form = xform("2", bindType = "int")) }
        assertTrue(scratch.root.listFiles().orEmpty().isEmpty())
    }

    @Test fun rejectsChoiceFormsAndRelativePathReferences() {
        reject {
            session(form = xform("2", extraBody = """
                <select1 ref="/data/name">
                  <item><label>One</label><value>1</value></item>
                </select1>
            """.trimIndent()))
        }
        reject {
            session(form = xform("2").replace(
                """nodeset="/data/name"""", """nodeset="name""""
            ))
        }
    }

    @Test fun preventsFormExternalResources() {
        val injection = xform("2").replace("</instance>",
            """</instance><instance id="external" src="https://host.invalid/pii.xml"/>""")
        reject { session(form = injection) }
        assertTrue(scratch.root.listFiles().orEmpty().isEmpty())
    }

    private fun reject(block: () -> Unit) {
        var denied = false
        try { block() } catch (_: Exception) { denied = true }
        assertTrue("Unsupported form or answer must not be saved", denied)
    }
}
