package org.odk.collect.android.recordwork

import org.odk.collect.settings.SettingsProvider
import java.io.File

/**
 * Read-only, online-authorized repeat image association gate. The UUID
 * ledger and authenticated ciphertext are the source of truth; native
 * multiplicities are derived anew under the same process-local lock.
 * No native instance, attachment file, finalization or upload is created.
 */
internal class ProtectedNativeRepeatImageProbe(
    private val scratch: File,
    private val root: File,
    private val settings: SettingsProvider
) {
    fun inspect(
        projectId: String,
        binding: QuarantinedWorkDraft
    ): Map<Pair<String, String>, Int> {
        require(binding.workType == RecordWorkType.FOLLOW_UP &&
            binding.validationTransfers == 0)
        val authorized = RecordWorkPreparationClient(settings)
            .authorizePrivateTextForm(projectId, binding)
        val cipher = AndroidKeyStorePrivateDraftCipher()
        val approval = requireNotNull(ProtectedMappingApprovalStore(root, cipher)
            .read(binding, authorized.plan))
        val session = JavaRosaPrivateRepeatAnswerSession(
            scratch, binding, authorized.plan, authorized.targetXFormXml)
        require(session.schema.groups.size == 1)
        val group = session.schema.groups.single()
        require(group.imagePaths.isNotEmpty() &&
            (group.fields.map { it.path } + group.imagePaths).all {
                approval.decisions[it] == MappingDecision.REENTER
            })
        val rows = QuarantinedRepeatRowVault(root, cipher)
        val store = QuarantinedRepeatImageStore(root, cipher, rows)
        return rows.withLockedCurrentInventory(binding, session.schema) { inventory ->
            val receipts = linkedMapOf<Pair<String, String>, ProtectedRepeatImageReceipt>()
            for (row in inventory.active(group.path)) {
                for (path in group.imagePaths) {
                    store.withVerifiedImage(binding, session, row.rowId, path) { receipt, bytes ->
                        require(receipt.rowId == row.rowId && receipt.fieldPath == path &&
                            receipt.bytes == bytes.size &&
                            receipt.sha256 == QuarantinedPrivateImageStore.digest(bytes))
                        receipts[row.rowId to path] = receipt
                    }
                }
            }
            session.verifyImageReferences(inventory, receipts)
        }
    }
}
