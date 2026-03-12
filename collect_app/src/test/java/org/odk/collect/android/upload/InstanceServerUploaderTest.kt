package org.odk.collect.android.upload

import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.equalTo
import org.junit.Test
import org.odk.collect.shared.TempFiles.createTempDir
import java.io.File

class InstanceServerUploaderTest {

    @Test
    fun `getSubmissionFile returns instance xml when both instance and submission xml are present`() {
        val instanceDir = createTempDir()
        val instanceFile = File(instanceDir, "instance.xml").apply { writeText("<data />") }
        File(instanceDir, "submission.xml").apply { writeText("<data>older</data>") }

        val result = InstanceServerUploader.getSubmissionFile(instanceFile)

        assertThat(result.absolutePath, equalTo(instanceFile.absolutePath))
    }

    @Test
    fun `getSubmissionFile returns submission xml when instance xml is missing`() {
        val instanceDir = createTempDir()
        val instanceFile = File(instanceDir, "instance.xml")
        val submissionFile = File(instanceDir, "submission.xml").apply { writeText("<data />") }

        val result = InstanceServerUploader.getSubmissionFile(instanceFile)

        assertThat(result.absolutePath, equalTo(submissionFile.absolutePath))
    }
}
