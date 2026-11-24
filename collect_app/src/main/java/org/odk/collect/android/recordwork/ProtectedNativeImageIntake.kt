package org.odk.collect.android.recordwork

import org.odk.collect.settings.SettingsProvider
import java.io.File
import java.io.InputStream

/**
 * Narrow bridge for direct image fields in mixed native forms. Each operation
 * reauthorizes the work, checks the encrypted review and qualifies the actual
 * binary question. The existing encrypted store remains the sole byte owner.
 * No ordinary ODK instance, answer filename, media URI or upload is exposed.
 */
internal class ProtectedNativeImageIntake(
    scratchRoot: File,
    noBackupRoot: File,
    settings: SettingsProvider
) {
    private val probe = ProtectedNativeControllerProbe(scratchRoot, noBackupRoot, settings)
    private val store = QuarantinedPrivateImageStore(
        noBackupRoot, AndroidKeyStorePrivateDraftCipher()
    )

    fun inspect(projectId: String, binding: QuarantinedWorkDraft, path: String):
        ProtectedNativeControllerEvidence =
        probe.inspect(projectId, binding, directImagePath = path).also {
            require(it.directImagePath == path && it.nativeImageReferenceVerified &&
                !it.canSave && !it.canFinalize && !it.canUpload)
        }

    fun importSelectedImage(
        projectId: String,
        binding: QuarantinedWorkDraft,
        path: String,
        mime: String,
        source: InputStream
    ): ProtectedImageReceipt = source.use { input ->
        inspect(projectId, binding, path)
        val receipt = store.importSelectedImage(binding, path, listOf(path), mime, input)
        // Never delete a successful encrypted replacement on a subsequent
        // network/readback failure; it remains quarantined for reauthorization.
        require(inspect(projectId, binding, path).directImageReceipt == receipt) {
            "Protected image failed native readback"
        }
        receipt
    }

    fun clear(projectId: String, binding: QuarantinedWorkDraft, path: String) {
        inspect(projectId, binding, path)
        store.clear(binding, path, listOf(path))
        require(inspect(projectId, binding, path).directImageReceipt == null)
    }
}
