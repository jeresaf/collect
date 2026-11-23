package org.odk.collect.android.recordwork

import android.content.Context
import android.widget.Button

/**
 * The browser displays server-owned READY work separately from submitted
 * records. Labels distinguish later visits from amendments and never expose
 * answers, credentials, source XML or attachments.
 *
 * Selecting a row is still only a navigation hint: work state, ownership and
 * target authorization are independently checked during preparation.
 */
internal object RecordWorkPendingRow {
    fun create(context: Context, work: RemoteVisitWork, onSelect: (RemoteVisitWork) -> Unit): Button =
        Button(context).apply {
            val kind = when (work.type) {
                RecordWorkType.FOLLOW_UP -> "Independent follow-up visit"
                RecordWorkType.AMENDMENT -> "Pending amendment"
            }
            text = "$kind\nWork: ${work.id}\nSource: ${work.sourceSubmissionId}" +
                if (work.type == RecordWorkType.FOLLOW_UP) {
                    "\nVisit: ${work.visitKey.orEmpty()}"
                } else {
                    ""
                }
            isAllCaps = false
            setOnClickListener { onSelect(work) }
        }
}
