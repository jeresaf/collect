package org.odk.collect.android.recordwork

import android.content.Context
import android.content.Intent
import java.util.UUID

/**
 * Single navigation boundary for the record browser. All extras remain hints:
 * RecordWorkPrepareActivity must independently match the active project and
 * the server must freshly authorize each target, amendment or follow-up.
 *
 * Never put answers, source XML, media or credentials into Intent extras.
 */
internal object RecordWorkNavigation {
    fun record(context: Context, activeProjectId: String, selected: RemoteRecord): Intent {
        requireCanonicalUuid(activeProjectId)
        requireCanonicalUuid(selected.submissionId)
        return Intent(context, RecordWorkPrepareActivity::class.java).apply {
            putExtra(RecordWorkPrepareActivity.EXTRA_PROJECT, activeProjectId)
            putExtra(RecordWorkPrepareActivity.EXTRA_SOURCE, selected.submissionId)
            putExtra(RecordWorkPrepareActivity.EXTRA_SOURCE_CAN_AMEND,
                selected.mayOfferAmendment)
        }
    }

    fun pending(context: Context, activeProjectId: String, selected: RemoteVisitWork): Intent {
        requireCanonicalUuid(activeProjectId)
        requireCanonicalUuid(selected.id)
        requireCanonicalUuid(selected.sourceSubmissionId)
        requireCanonicalUuid(selected.targetFormVersionId)
        return Intent(context, RecordWorkPrepareActivity::class.java).apply {
            putExtra(RecordWorkPrepareActivity.EXTRA_PROJECT, activeProjectId)
            putExtra(RecordWorkPrepareActivity.EXTRA_SOURCE, selected.sourceSubmissionId)
            putExtra(RecordWorkPrepareActivity.EXTRA_WORK, selected.id)
            putExtra(RecordWorkPrepareActivity.EXTRA_TARGET, selected.targetFormVersionId)
            putExtra(RecordWorkPrepareActivity.EXTRA_TYPE, selected.type.name)
            // No forged source-can-amend hint on a preexisting work item:
            // the active server work ID is authorized again on prepare.
        }
    }

    private fun requireCanonicalUuid(value: String) {
        require(runCatching { UUID.fromString(value).toString() == value }.getOrDefault(false)) {
            "Invalid record-work navigation identifier"
        }
    }
}
