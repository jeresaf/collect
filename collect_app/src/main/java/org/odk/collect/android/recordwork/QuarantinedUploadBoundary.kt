package org.odk.collect.android.recordwork

import org.odk.collect.android.instancemanagement.send.FormUploadException
import java.io.File

/**
 * Defense in depth for every ordinary ODK uploader. Native ODK HTTP upload
 * does not add X-DataCollector-Work-Item-Id. A quarantined work record MUST
 * therefore never be processed through that transport, even if accidentally
 * inserted into the standard InstancesRepository by future code.
 *
 * This does not authorize an alternative upload. A dedicated future sender
 * must bind work, project, instance IDs, exact version, approved mapping and
 * the server header without redirect/host changes before sending.
 */
object QuarantinedUploadBoundary {
    fun ensureOrdinaryUploadAllowed(instanceFilePath: String) {
        require(instanceFilePath.isNotBlank()) { "Missing instance path" }
        val parsed = File(instanceFilePath)
        val directory = QuarantinedWorkDraftVault.VAULT_DIRECTORY
        // Do not normalize away a "recordwork-drafts/../" traversal: deny
        // either raw or canonical references to the reserved vault segment.
        val rawSegments = parsed.path.replace('\\', '/').split('/')
        val canonicalSegments = try {
            parsed.canonicalPath.replace('\\', '/').split('/')
        } catch (_: Exception) {
            // Unreadable/hostile path is not safe to upload.
            throw FormUploadException("Cannot resolve instance path")
        }
        if (directory in rawSegments || directory in canonicalSegments) {
            throw FormUploadException(
                "Quarantined record work cannot use the ordinary ODK uploader"
            )
        }
    }
}
