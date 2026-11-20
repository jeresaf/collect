package org.odk.collect.android.recordwork

import org.javarosa.model.xform.XFormsModule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Uses native JavaRosa target forms, not a reconstructed source submission. */
class JavaRosaPrivateRepeatStructureProbeTest {
    @get:Rule val scratch = TemporaryFolder()
    private val work = "e6bddba9-e98b-49cb-8bed-dce91d400520"
    private val source = "a8c07f51-b4c1-40a4-b31c-956d2ca5e048"
    private val target = "5a6f0bf6-795c-4d91-9861-16910542b4f1"
    private val old = """
        <data id="household" version="1">
          <meta><instanceID>uuid:9e1e9e90-5cc8-4e65-a5d0-acb5bdc1500d</instanceID></meta>
        </data>
    """.trimIndent()

    @Before fun register() { XFormsModule().registerModule() }

    private fun form(
        version: String = "2",
        repeatControl: String = """
            <repeat nodeset="/data/members">
              <input ref="/data/members/name"><label>Member name</label></input>
              <input ref="/data/members/age"><label>Member age</label></input>
            </repeat>
        """.trimIndent(),
        extraBinding: String = "",
        extraField: String = "",
        extraControl: String = ""
    ) = """
        <h:html xmlns:h="http://www.w3.org/1999/xhtml"
                xmlns="http://www.w3.org/2002/xforms">
          <h:head><h:title>Members of a household</h:title>
            <model><instance>
              <data id="household" version="$version">
                <members><name/><age/>$extraField</members>
                <meta><instanceID/></meta>
              </data>
            </instance>
              <bind nodeset="/data/members/name" type="string"/>
              <bind nodeset="/data/members/age" type="int"/>
              $extraBinding
            </model>
          </h:head>
          <h:body>
            $repeatControl
            $extraControl
          </h:body>
        </h:html>
    """.trimIndent()

    private fun probe(
        targetXml: String = form(),
        reviewedXml: String = targetXml
    ): JavaRosaPrivateRepeatStructureProbe {
        val plan = HistoricalSchemaMappingEngine.plan(
            work, source, target, RecordWorkType.FOLLOW_UP,
            old, null, reviewedXml, "household", "2"
        )
        val binding = QuarantinedWorkDraft(
            projectId = "local-project", actorContextSha256 = "a".repeat(64),
            workId = work, sourceSubmissionId = source,
            sourceInstanceId = "uuid:9e1e9e90-5cc8-4e65-a5d0-acb5bdc1500d",
            targetFormVersionId = target, targetFormId = "household",
            targetVersion = "2", workType = RecordWorkType.FOLLOW_UP,
            visitKey = "visit-2",
            reservedInstanceId = "uuid:aaaaaaaa-aaaa-4aaa-aaaa-aaaaaaaaaaaa",
            sourceXmlSha256 = JavaRosaPrivateScalarSession.digest(old),
            sourceXFormSha256 = plan.sourceXFormSha256,
            targetXFormSha256 = plan.targetXFormSha256,
            reviewedPaths = plan.candidates.size, validationTransfers = 0,
            requiresDeprecatedId = false
        )
        return JavaRosaPrivateRepeatStructureProbe(
            scratch.root, binding, plan, targetXml
        )
    }

    @Test fun realNativeRepeatTemplateBindsDirectScalarFields() {
        val structure = probe().schema
        assertEquals(work, structure.workId)
        assertEquals(1, structure.groups.size)
        assertEquals("/data/members", structure.groups.single().path)
        assertEquals(
            listOf("/data/members/name", "/data/members/age"),
            structure.groups.single().fields.map { it.path }
        )
        assertEquals(
            listOf(PrivateScalarType.TEXT, PrivateScalarType.INTEGER),
            structure.groups.single().fields.map { it.type }
        )
        assertFalse(structure.canSaveAnswers)
        assertFalse(structure.canFinalize)
        assertFalse(structure.canUpload)
        assertTrue(scratch.root.listFiles().orEmpty().isEmpty())
    }

    @Test fun updatedTargetVersionAndHistoricalDataCannotBeGuessed() {
        val approved = form()
        reject { probe(targetXml = approved.replace("Member age", "Changed meaning"),
            reviewedXml = approved) }
        reject { probe(targetXml = form(version = "3")) }
        assertTrue(scratch.root.listFiles().orEmpty().isEmpty())
    }

    @Test fun nestedRepeatMediaAndDynamicControlsCannotReserveFlatIds() {
        reject { probe(targetXml = form(extraControl = """
            <repeat nodeset="/data/other">
              <input ref="/data/other/name"><label>Hidden</label></input>
            </repeat>
        """.trimIndent())) }
        reject { probe(targetXml = form(
            repeatControl = """
                <repeat nodeset="/data/members">
                  <repeat nodeset="/data/members/children">
                    <input ref="/data/members/children/name">
                      <label>Child</label>
                    </input>
                  </repeat>
                </repeat>
            """.trimIndent())) }
        reject { probe(targetXml = form(extraControl = """
            <upload ref="/data/image" mediatype="image/*">
              <label>Old attachment</label>
            </upload>
        """.trimIndent())) }
        reject { probe(targetXml = form(extraControl = """
            <select1 ref="/data/members/age"><label>Choices</label>
              <itemset nodeset="instance('remote')/items/item">
                <label ref="label"/><value ref="code"/>
              </itemset>
            </select1>
        """.trimIndent())) }
    }

    @Test fun targetHiddenFieldsAndCalculatedRulesAreDenied() {
        reject { probe(targetXml = form(
            extraBinding = """<bind nodeset="/data/members/hidden" type="string"/>""",
            extraField = "<hidden/>"
        )) }
        reject { probe(targetXml = form(
            extraBinding = """<bind nodeset="/data/members/age" calculate="1 + 2" type="int"/>"""
        )) }
        reject { probe(targetXml = form(
            extraBinding = """<bind nodeset="/data/members/age" relevant="/data/flag = 1" type="int"/>"""
        )) }
        reject { probe(targetXml = form(
            repeatControl = """
              <repeat nodeset="/data/members">
                <input ref="/data/members/name"><label>Name</label></input>
              </repeat>
            """.trimIndent()
        )) }
    }

    @Test fun externalFormResourcesAndUnsafeXmlDoNotRun() {
        reject { probe(targetXml = form().replace(
            "</instance>", """</instance><instance id="external" src="https://bad.invalid/exfil"/>"""
        )) }
        reject { probe(targetXml = form().replace(
            "</model>", "<!ENTITY x SYSTEM 'file:///etc/passwd'></model>"
        )) }
        reject { probe(targetXml = form(
            repeatControl = """<repeat nodeset="members">
                 <input ref="/data/members/name"><label>N</label></input>
               </repeat>"""
        )) }
    }

    private fun reject(block: () -> Unit) {
        var denied = false
        try { block() } catch (_: Exception) { denied = true }
        assertTrue("Unapproved repeat form/field must be rejected", denied)
    }
}
