package org.odk.collect.android.entrymanagement

import android.content.Context
import org.odk.collect.android.R
import org.odk.collect.strings.localization.getLocalizedString

class EntryDownloadExceptionMapper(private val context: Context) {
    fun getMessage(exception: EntryDownloadException?): String {
        return when (exception) {
            is EntryDownloadException.EntryWithNoHash -> {
                context.getLocalizedString(
                    R.string.form_with_no_hash_error
                ) + " " + context.getLocalizedString(
                    R.string.report_to_project_lead
                )
            }
            is EntryDownloadException.EntryParsingError -> {
                context.getLocalizedString(
                    R.string.form_parsing_error
                ) + " " + context.getLocalizedString(
                    R.string.report_to_project_lead
                )
            }
            is EntryDownloadException.DiskError -> {
                context.getLocalizedString(
                    R.string.form_save_disk_error
                ) + " " + context.getLocalizedString(
                    R.string.report_to_project_lead
                )
            }
            is EntryDownloadException.InvalidSubmission -> {
                context.getLocalizedString(
                    R.string.form_with_invalid_submission_error
                ) + " " + context.getLocalizedString(
                    R.string.report_to_project_lead
                )
            }
            is EntryDownloadException.EntrySourceError -> {
                EntrySourceExceptionMapper(context).getMessage(exception.exception)
            }
            else -> {
                context.getLocalizedString(R.string.report_to_project_lead)
            }
        }
    }
}
