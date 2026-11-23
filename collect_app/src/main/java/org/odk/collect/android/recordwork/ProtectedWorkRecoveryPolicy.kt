package org.odk.collect.android.recordwork

/**
 * A read-only, fail-closed decision for an already quarantined local draft.
 *
 * The server is authoritative on every open and save. This policy cannot
 * unlock a native instance, authorize source data, retry a submission, or
 * modify the AES-GCM stores. It exposes fixed, non-sensitive messages only:
 * NEVER put the exception body or historic answers into a status view.
 */
enum class ProtectedWorkFailure {
    STALE_OR_COMPLETED, ACCESS_REVOKED, OFFLINE_RETRY, BLOCKED
}

class ProtectedWorkRecovery private constructor(val failure: ProtectedWorkFailure) {
    val preserveSavedEncryptedDraft: Boolean get() = true
    val canOpenEditor: Boolean get() = false
    val canSave: Boolean get() = false
    val canFinalize: Boolean get() = false
    val canUpload: Boolean get() = false
    val mayAutomaticallyRetargetSource: Boolean get() = false
    val mayAutomaticallyRetrySubmission: Boolean get() = false

    val message: String
        get() = when (failure) {
            ProtectedWorkFailure.STALE_OR_COMPLETED ->
                "The server record was amended or this work item was completed. " +
                "Editing is blocked. Previously saved encrypted answers stay on this device. " +
                "Refresh the record online and review a newly authorized work item; do not resubmit this draft."
            ProtectedWorkFailure.ACCESS_REVOKED ->
                "This account, form, or work item is no longer authorized. " +
                "Editing is blocked; previously saved encrypted drafts are not erased. " +
                "Sign in again or contact the administrator before reviewing this record."
            ProtectedWorkFailure.OFFLINE_RETRY ->
                "The server cannot be reached. No offline authorization is assumed. " +
                "Previously saved encrypted answers remain on this device. " +
                "Reconnect and revalidate this exact work item before saving."
            ProtectedWorkFailure.BLOCKED ->
                "Protected editing is unavailable. No answers have been submitted. " +
                "Previously saved encrypted drafts are retained for an authorized review."
        }

    companion object {
        fun forFailure(error: Throwable): ProtectedWorkRecovery {
            val reason = (error as? PreparationException)?.reason
            val failure = when (reason) {
                PreparationError.CONFLICT -> ProtectedWorkFailure.STALE_OR_COMPLETED
                PreparationError.LOGIN_REQUIRED,
                PreparationError.ACCESS_DENIED,
                PreparationError.NOT_FOUND,
                PreparationError.MISSING_CREDENTIALS,
                PreparationError.INVALID_PROJECT -> ProtectedWorkFailure.ACCESS_REVOKED
                PreparationError.NETWORK -> ProtectedWorkFailure.OFFLINE_RETRY
                else -> ProtectedWorkFailure.BLOCKED
            }
            return ProtectedWorkRecovery(failure)
        }
    }
}
