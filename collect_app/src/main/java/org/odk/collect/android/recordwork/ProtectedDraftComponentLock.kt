package org.odk.collect.android.recordwork

/**
 * All mutable draft component stores share this reentrant, process-local
 * monitor. No network request may be initiated while capturing a snapshot.
 * This is not a multiprocess file lock or a server authorization.
 */
internal object ProtectedDraftComponentLock {
    val monitor = Any()
}
