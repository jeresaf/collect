package org.odk.collect.android.recordwork

import org.odk.collect.android.BuildConfig
import org.javarosa.core.model.Constants
import org.javarosa.core.model.data.StringData
import org.javarosa.core.model.instance.TreeElement
import java.util.UUID

/** Deliberately narrow: only reviewed re-entry, never historical transfer. */
internal object ProtectedReentryWork {
    fun eligible(b: QuarantinedWorkDraft,
                 amendmentsEnabled: Boolean = BuildConfig.PROTECTED_BOUND_SUBMISSIONS && BuildConfig.PROTECTED_AMENDMENTS): Boolean =
        b.validationTransfers == 0 && b.sourceInstanceId != b.reservedInstanceId &&
            when (b.workType) {
                RecordWorkType.FOLLOW_UP -> !b.requiresDeprecatedId && !b.visitKey.isNullOrBlank()
                RecordWorkType.AMENDMENT -> amendmentsEnabled && b.requiresDeprecatedId &&
                    b.visitKey == null && b.sourceXFormSha256 != null
            }

    /** Only metadata owned by the protected protocol may be present. */
    fun prepareNativeMetadata(b: QuarantinedWorkDraft, meta: TreeElement) {
        require(eligible(b) && meta.name == "meta")
        val expected = metadata(b)
        val existing = (0 until meta.numChildren).map { meta.getChildAt(it).name }
        require(existing.size == existing.toSet().size &&
            "instanceID" in existing && existing.all { it in expected })
        val instance = requireNotNull(meta.getChild("instanceID", 0))
        require(instance.isLeaf && instance.value == null)
        if (b.requiresDeprecatedId) {
            val deprecated = meta.getChild("deprecatedID", 0) ?: TreeElement("deprecatedID", 0).apply {
                dataType = Constants.DATATYPE_TEXT
                meta.addChild(this)
            }
            require(deprecated.isLeaf && deprecated.value == null &&
                deprecated.dataType == Constants.DATATYPE_TEXT)
            deprecated.setValue(StringData(b.sourceInstanceId))
            require(deprecated.value?.value == b.sourceInstanceId)
        }
        require(meta.numChildren == expected.size &&
            (0 until meta.numChildren).map { meta.getChildAt(it).name }.toSet() == expected.keys)
    }

    /** Exact protocol-owned metadata; structural validation, not authorization. */
    fun metadata(b: QuarantinedWorkDraft): Map<String, String> {
        require(eligible(b, amendmentsEnabled = true))
        for (id in listOf(b.reservedInstanceId, b.sourceInstanceId)) {
            require(id.startsWith("uuid:") && UUID.fromString(id.removePrefix("uuid:")).toString() == id.removePrefix("uuid:"))
        }
        return if (b.requiresDeprecatedId)
            linkedMapOf("instanceID" to b.reservedInstanceId, "deprecatedID" to b.sourceInstanceId)
        else mapOf("instanceID" to b.reservedInstanceId)
    }
}
