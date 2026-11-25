package org.odk.collect.android.recordwork

import org.odk.collect.settings.SettingsProvider
import java.io.File

/**
 * Read-only, online-authorized repeat image association gate. The UUID
 * ledger and authenticated ciphertext are the source of truth; native
 * multiplicities are derived anew under the same process-local lock.
 * No native instance, attachment file, finalization or upload is created.
 */
internal data class ProtectedCombinedRepeatEvidence(
    val imageOrdinals: Map<Pair<String, String>, Int>,
    val savedRowAnswerDigests: Map<String, Map<String, String>>,
    val directAnswerDigests: Map<String, String> = emptyMap(),
    val directImageDigests: Map<String, String> = emptyMap()
)

internal class ProtectedNativeRepeatImageProbe(
    private val scratch: File,
    private val root: File,
    private val settings: SettingsProvider
) {
    fun inspect(
        projectId: String,
        binding: QuarantinedWorkDraft
    ): Map<Pair<String, String>, Int> =
        inspectCombined(projectId, binding).imageOrdinals

    /** Narrow direct text save; exact mixed target and complete review are mandatory. */
    fun saveDirectText(
        projectId: String, binding: QuarantinedWorkDraft, values: Map<String, String>,
        expectedValues: Map<String, String>? = null
    ) {
        require(binding.workType == RecordWorkType.FOLLOW_UP && binding.validationTransfers == 0)
        val authorized = RecordWorkPreparationClient(settings)
            .authorizePrivateTextForm(projectId, binding)
        val cipher = AndroidKeyStorePrivateDraftCipher()
        val approval = requireNotNull(ProtectedMappingApprovalStore(root, cipher)
            .read(binding, authorized.plan))
        require(approval.decisions.values.all { it == MappingDecision.REENTER })
        val session = JavaRosaPrivateRepeatAnswerSession(
            scratch, binding, authorized.plan, authorized.targetXFormXml, true)
        val validated = session.validateDirectText(values)
        val store = EncryptedPrivateTextDraftStore(root, cipher)
        synchronized(ProtectedDraftComponentLock.monitor) {
            if (expectedValues != null) {
                val prior = store.read(binding, session.schema.directTextPaths).answers
                require(expectedValues.keys == session.schema.directTextPaths.toSet() &&
                    session.schema.directTextPaths.associateWith { prior[it].orEmpty() } == expectedValues) {
                    "Working answers changed; reload before saving"
                }
            }
            store.save(binding, validated, session.schema.directTextPaths)
            require(store.read(binding, session.schema.directTextPaths).answers == validated)
        }
    }

    /** Rebuild saved scalar values and authenticated images in the same native model. */
    fun inspectCombined(
        projectId: String,
        binding: QuarantinedWorkDraft,
        allowMixedDirect: Boolean = false,
        requireComplete: Boolean = false
    ): ProtectedCombinedRepeatEvidence {
        require(!requireComplete || allowMixedDirect)
        require(binding.workType == RecordWorkType.FOLLOW_UP &&
            binding.validationTransfers == 0)
        val authorized = RecordWorkPreparationClient(settings)
            .authorizePrivateTextForm(projectId, binding)
        val cipher = AndroidKeyStorePrivateDraftCipher()
        val approval = requireNotNull(ProtectedMappingApprovalStore(root, cipher)
            .read(binding, authorized.plan))
        val session = JavaRosaPrivateRepeatAnswerSession(
            scratch, binding, authorized.plan, authorized.targetXFormXml, allowMixedDirect)
        require(session.schema.groups.size == 1)
        val group = session.schema.groups.single()
        require(group.imagePaths.isNotEmpty() &&
            (group.fields.map { it.path } + group.imagePaths +
                session.schema.directTextPaths + session.schema.directImagePaths).all {
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
            val saved = QuarantinedRepeatAnswerStore(root, cipher, rows)
                .readAllSaved(binding, session)
            val direct = if (session.schema.directTextPaths.isEmpty()) emptyMap()
                else EncryptedPrivateTextDraftStore(root, cipher)
                    .read(binding, session.schema.directTextPaths).answers
            val directImages = linkedMapOf<String, ProtectedImageReceipt>()
            val directStore = QuarantinedPrivateImageStore(root, cipher)
            for (path in session.schema.directImagePaths) {
                directStore.withVerifiedImage(binding, path, session.schema.directImagePaths) {
                    receipt, bytes ->
                    require(receipt.sha256 == QuarantinedPrivateImageStore.digest(bytes))
                    directImages[path] = receipt
                }
            }
            val ordinals = session.verifyImageReferences(
                inventory, receipts, saved, direct, directImages, requireComplete)
            ProtectedCombinedRepeatEvidence(ordinals, saved.mapValues { (_, values) ->
                values.mapValues { (_, value) -> QuarantinedWorkDraftVault.sha256(value) }
            }, direct.mapValues { (_, value) -> QuarantinedWorkDraftVault.sha256(value) },
                directImages.mapValues { (_, receipt) -> receipt.sha256 })
        }
    }
}
