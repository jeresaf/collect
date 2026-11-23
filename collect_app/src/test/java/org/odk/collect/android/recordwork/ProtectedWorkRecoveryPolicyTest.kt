package org.odk.collect.android.recordwork

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * No Android device, credentials, real records, or private vault contents.
 * This classifier deliberately has no file or network dependency.
 */
class ProtectedWorkRecoveryPolicyTest {
    @Test fun staleAndCompletedWorkCannotAcquireEditingOrRetryAuthority() {
        val recovered = ProtectedWorkRecovery.forFailure(
            PreparationException(PreparationError.CONFLICT)
        )
        assertEquals(ProtectedWorkFailure.STALE_OR_COMPLETED, recovered.failure)
        assertTrue(recovered.preserveSavedEncryptedDraft)
        assertTrue(recovered.message.contains("Refresh the record online"))
        assertFalse(recovered.canOpenEditor)
        assertFalse(recovered.canSave)
        assertFalse(recovered.canFinalize)
        assertFalse(recovered.canUpload)
        assertFalse(recovered.mayAutomaticallyRetargetSource)
        assertFalse(recovered.mayAutomaticallyRetrySubmission)
    }

    @Test fun outageOnlyAllowsExplicitOnlineReauthorizationLater() {
        val recovered = ProtectedWorkRecovery.forFailure(
            PreparationException(PreparationError.NETWORK)
        )
        assertEquals(ProtectedWorkFailure.OFFLINE_RETRY, recovered.failure)
        assertTrue(recovered.preserveSavedEncryptedDraft)
        assertTrue(recovered.message.contains("Reconnect"))
        assertFalse(recovered.canSave)
        assertFalse(recovered.canUpload)
        assertFalse(recovered.mayAutomaticallyRetrySubmission)
    }

    @Test fun projectOwnershipAndCredentialsChangesFailClosedWithoutPurgingDisk() {
        for (reason in listOf(
            PreparationError.LOGIN_REQUIRED,
            PreparationError.ACCESS_DENIED,
            PreparationError.NOT_FOUND,
            PreparationError.MISSING_CREDENTIALS,
            PreparationError.INVALID_PROJECT
        )) {
            val recovered = ProtectedWorkRecovery.forFailure(
                PreparationException(reason)
            )
            assertEquals(ProtectedWorkFailure.ACCESS_REVOKED, recovered.failure)
            assertTrue(recovered.preserveSavedEncryptedDraft)
            assertFalse(recovered.canOpenEditor)
            assertFalse(recovered.canFinalize)
        }
    }

    @Test fun unexpectedExceptionsNeverLeakMessagesOrGrantSubmission() {
        val sensitive = "Original household answer must never render"
        val recovered = ProtectedWorkRecovery.forFailure(
            IllegalStateException(sensitive)
        )
        assertEquals(ProtectedWorkFailure.BLOCKED, recovered.failure)
        assertFalse(recovered.message.contains(sensitive))
        assertTrue(recovered.preserveSavedEncryptedDraft)
        assertFalse(recovered.canUpload)
        assertFalse(recovered.mayAutomaticallyRetargetSource)
        for (reason in listOf(
            PreparationError.BAD_RESPONSE,
            PreparationError.SERVER_ERROR,
            PreparationError.REDIRECT_BLOCKED
        )) {
            val other = ProtectedWorkRecovery.forFailure(
                PreparationException(reason)
            )
            assertEquals(ProtectedWorkFailure.BLOCKED, other.failure)
            assertFalse(other.canSave)
        }
    }
}
