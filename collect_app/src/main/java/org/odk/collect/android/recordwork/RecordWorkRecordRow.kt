package org.odk.collect.android.recordwork

import android.content.Context
import android.widget.Button

/**
 * Renders a *server-provided* record as an Android choice.
 *
 * A CURRENT marker is a visual hint only, never permission to amend: the
 * backend must still check authorization and the source revision in its
 * transaction. UNKNOWN and SUPERSEDED both fail closed for AMENDMENT, while
 * the independent FOLLOW_UP workflow remains eligible for server review.
 *
 * This factory is used by the actual RecordWorkBrowserActivity, not a parallel
 * test-only screen. Its callback carries the selected server record unchanged.
 */
internal object RecordWorkRecordRow {
    fun create(
        context: Context,
        record: RemoteRecord,
        onSelect: (RemoteRecord) -> Unit
    ): Button = Button(context).apply {
        val amendmentLabel = when (record.amendmentRevision) {
            RemoteAmendmentRevision.CURRENT ->
                "No archived amendment shown (server rechecks before creating work)"
            RemoteAmendmentRevision.SUPERSEDED ->
                "Historical record — already amended; choose its newer revision"
            RemoteAmendmentRevision.UNKNOWN ->
                "Revision status unavailable — amendment creation blocked"
        }
        text = "${record.submittedAt}\n${record.submissionId}\n" +
            "Form: ${record.formId}\n$amendmentLabel"
        isAllCaps = false
        setOnClickListener { onSelect(record) }
    }
}
