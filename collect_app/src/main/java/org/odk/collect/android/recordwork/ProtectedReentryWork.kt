package org.odk.collect.android.recordwork

import org.odk.collect.android.BuildConfig
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
