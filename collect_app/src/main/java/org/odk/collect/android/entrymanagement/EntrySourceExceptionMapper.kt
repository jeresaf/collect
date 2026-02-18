package org.odk.collect.android.entrymanagement

import android.content.Context
import org.odk.collect.android.R
import org.odk.collect.forms.FormSourceException
import org.odk.collect.forms.entries.EntrySourceException
import org.odk.collect.strings.localization.getLocalizedString

class EntrySourceExceptionMapper(private val context: Context) {
    fun getMessage(exception: EntrySourceException?): String {
        return when (exception) {
            is EntrySourceException.Unreachable -> {
                context.getLocalizedString(
                    R.string.unreachable_error,
                    exception.serverUrl
                ) + " " + context.getLocalizedString(
                    R.string.report_to_project_lead
                )
            }
            is EntrySourceException.SecurityError -> {
                context.getLocalizedString(
                    R.string.security_error,
                    exception.serverUrl
                ) + " " + context.getLocalizedString(
                    R.string.report_to_project_lead
                )
            }
            is EntrySourceException.ServerError -> {
                context.getLocalizedString(
                    R.string.server_error,
                    exception.serverUrl,
                    exception.statusCode
                ) + " " + context.getLocalizedString(
                    R.string.report_to_project_lead
                )
            }
            is EntrySourceException.ParseError -> {
                context.getLocalizedString(
                    R.string.invalid_response,
                    exception.serverUrl
                ) + " " + context.getLocalizedString(
                    R.string.report_to_project_lead
                )
            }
            is EntrySourceException.ServerNotOpenRosaError -> {
                "This server does not correctly implement the OpenRosa formList API." + " " + context.getLocalizedString(
                    R.string.report_to_project_lead
                )
            }
            else -> {
                context.getLocalizedString(R.string.report_to_project_lead)
            }
        }
    }
}
