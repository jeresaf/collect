package org.odk.collect.android.recordwork

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.verifyNoInteractions
import org.odk.collect.android.instancemanagement.send.FormUploadException
import org.odk.collect.android.instancemanagement.send.OpenRosaServerInstanceUploader
import org.odk.collect.android.projects.ProjectDependencyModule
import org.odk.collect.forms.instances.Instance
import org.odk.collect.openrosa.http.OpenRosaHttpInterface
import org.odk.collect.projects.ProjectDependencyFactory

/** Quarantined files must never reach the ordinary OpenRosa HTTP path. */
class QuarantinedUploadBoundaryTest {
    @Test fun normalOdKInstancePathRemainsAllowed() {
        QuarantinedUploadBoundary.ensureOrdinaryUploadAllowed(
            "/data/user/0/org.jeresaf.datacollector.android/files/instances/form_1/record.xml"
        )
        QuarantinedUploadBoundary.ensureOrdinaryUploadAllowed(null)
    }

    @Test fun vaultPathCannotBeUploaded() {
        reject {
            QuarantinedUploadBoundary.ensureOrdinaryUploadAllowed(
                "/data/user/0/org.jeresaf.datacollector.android/no_backup/" +
                    "recordwork-drafts/project/work/submission.xml"
            )
        }
    }

    @Test fun committedAndFinalizedPathsCannotBeUploaded() {
        for (path in listOf(
            "/tmp/recordwork-committed/project/work/finalized-id.aesgcm",
            "/tmp/recordwork-committed/../instances/fake.xml"
        )) reject { QuarantinedUploadBoundary.ensureOrdinaryUploadAllowed(path) }
    }

    @Test fun rawTraversalContainingReservedVaultIsAlsoDenied() {
        reject {
            QuarantinedUploadBoundary.ensureOrdinaryUploadAllowed(
                "/tmp/recordwork-drafts/../instances/fake.xml"
            )
        }
    }

    @Test fun ordinaryOpenRosaUploaderFailsBeforeAnyHttpRequest() {
        val dependencies = mock<ProjectDependencyFactory<ProjectDependencyModule>>()
        val http = mock<OpenRosaHttpInterface>()
        val uploader = OpenRosaServerInstanceUploader(dependencies, http)
        val instance = Instance.Builder()
            .formId("form")
            .formVersion("2")
            .status(Instance.STATUS_COMPLETE)
            .instanceFilePath("/tmp/recordwork-drafts/scope/work/answer.xml")
            .build()
        reject {
            uploader.uploadOneSubmission("project-id", instance, null, null, "", { false })
        }
        verifyNoInteractions(http)
        verifyNoInteractions(dependencies)
    }

    @Test fun privateVaultPathCheckerDoesNotEnableAnAlternateSender() {
        // Only permits ordinary records; it never generates work headers.
        QuarantinedUploadBoundary.ensureOrdinaryUploadAllowed("/tmp/instances/normal.xml")
        assertFalse(QuarantinedWorkDraft.STATE_QUARANTINED == "READY")
    }

    private fun reject(action: () -> Unit) {
        var blocked = false
        try { action() } catch (_: FormUploadException) { blocked = true }
        assertTrue("Quarantined work must be refused before upload", blocked)
    }
}
