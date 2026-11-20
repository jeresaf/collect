package org.odk.collect.android.recordwork

import org.javarosa.model.xform.XFormsModule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Real JavaRosa synthetic image upload controls, never source media. */
class JavaRosaPrivateImageFieldProbeTest {
    @get:Rule val scratch = TemporaryFolder()
    private val work = "e6bddba9-e98b-49cb-8bed-dce91d400520"
    private val source = "a8c07f51-b4c1-40a4-b31c-956d2ca5e048"
    private val target = "5a6f0bf6-795c-4d91-9861-16910542b4f1"
    private val historical = """
        <data id="household" version="1">
            <oldPhoto>old-attachment.jpg</oldPhoto>
            <meta><instanceID>uuid:9e1e9e90-5cc8-4e65-a5d0-acb5bdc1500d</instanceID></meta>
        </data>
    """.trimIndent()

    @Before fun register() { XFormsModule().registerModule() }

    private fun xform(
        version: String = "2",
        mediaType: String = "image/*",
        bindType: String = "binary",
        extraControl: String = "",
        fieldXml: String = "<newPhoto/>"
    ): String = """
        <h:html xmlns:h="http://www.w3.org/1999/xhtml"
                xmlns="http://www.w3.org/2002/xforms">
          <h:head><h:title>Household image field</h:title>
            <model><instance>
                <data id="household" version="$version">
                    $fieldXml<meta><instanceID/></meta>
                </data>
              </instance>
              <bind nodeset="/data/newPhoto" type="$bindType"/>
            </model>
          </h:head>
          <h:body>
            <upload ref="/data/newPhoto" mediatype="$mediaType">
              <label>New household image</label>
            </upload>
            $extraControl
          </h:body>
        </h:html>
    """.trimIndent()

    private fun probe(
        form: String = xform(),
        sourceForm: String? = null,
        boundTo: String = form
    ): JavaRosaPrivateImageFieldProbe {
        val plan = HistoricalSchemaMappingEngine.plan(
            work, source, target, RecordWorkType.FOLLOW_UP,
            historical, sourceForm, boundTo, "household", "2"
        )
        val binding = QuarantinedWorkDraft(
            projectId = "project", actorContextSha256 = "a".repeat(64),
            workId = work, sourceSubmissionId = source,
            sourceInstanceId = "uuid:9e1e9e90-5cc8-4e65-a5d0-acb5bdc1500d",
            targetFormVersionId = target, targetFormId = "household",
            targetVersion = "2", workType = RecordWorkType.FOLLOW_UP,
            visitKey = "visit-2",
            reservedInstanceId = "uuid:aaaaaaaa-aaaa-4aaa-aaaa-aaaaaaaaaaaa",
            sourceXmlSha256 = JavaRosaPrivateScalarSession.digest(historical),
            sourceXFormSha256 = plan.sourceXFormSha256,
            targetXFormSha256 = plan.targetXFormSha256,
            reviewedPaths = plan.candidates.size,
            validationTransfers = 0, requiresDeprecatedId = false
        )
        return JavaRosaPrivateImageFieldProbe(scratch.root, binding, plan, form)
    }

    @Test fun authorizedNewImageFieldUsesRealNativeJavaRosaBinaryControl() {
        val fields = probe().imagePaths
        assertEquals(listOf("/data/newPhoto"), fields)
        assertTrue(scratch.root.listFiles().orEmpty().isEmpty())
    }

    @Test fun rejectsNonImageAndNonBinaryControls() {
        rejects { probe(form = xform(mediaType = "audio/*")) }
        rejects { probe(form = xform(mediaType = "video/*")) }
        rejects { probe(form = xform(bindType = "string")) }
        assertTrue(scratch.root.listFiles().orEmpty().isEmpty())
    }

    @Test fun targetFormDriftAndExternalDataFailClosed() {
        val approved = xform()
        rejects {
            probe(form = approved.replace("New household image", "Changed label"),
                boundTo = approved)
        }
        rejects {
            probe(form = approved.replace(
                "</instance>",
                """</instance><instance id="external" src="https://host.invalid/image"/>"""
            ))
        }
        rejects { probe(form = approved.replace("</model>", "<!ENTITY attacker></model>")) }
    }

    @Test fun repeatOrDynamicMediaIsNotStagedAsFlatField() {
        rejects { probe(form = xform(extraControl = """
            <repeat nodeset="/data/members">
                <upload ref="/data/members/photo" mediatype="image/*">
                    <label>Person photo</label>
                </upload>
            </repeat>
        """.trimIndent())) }
        rejects { probe(form = xform(extraControl = """
            <select1 ref="/data/newPhoto">
                <itemset nodeset="instance('external')/root/item">
                    <label ref="name"/><value ref="value"/>
                </itemset>
            </select1>
        """.trimIndent())) }
    }

    @Test fun noMediaQuestionCannotBeImportedForAnotherField() {
        rejects { probe(form = xform(fieldXml = "<differentField/>")) }
        rejects { probe(form = xform().replace(
            """ref="/data/newPhoto"""", """ref="/data/otherPhoto""""
        )) }
    }

    private fun rejects(block: () -> Unit) {
        var denied = false
        try { block() } catch (_: Exception) { denied = true }
        assertTrue("Unverified media field must be denied", denied)
    }
}
