package org.odk.collect.android.utilities

import android.content.Context
import org.odk.collect.android.R
import org.odk.collect.android.entrymanagement.EntryDownloadException
import org.odk.collect.android.entrymanagement.EntryDownloadExceptionMapper
import org.odk.collect.android.entrymanagement.ServerEntryDetails
import org.odk.collect.android.formmanagement.FormDownloadException
import org.odk.collect.android.formmanagement.FormDownloadExceptionMapper
import org.odk.collect.android.formmanagement.ServerFormDetails
import org.odk.collect.errors.ErrorItem
import org.odk.collect.strings.localization.getLocalizedString

object EntriesDownloadResultInterpreter {
    fun getFailures(result: Map<ServerEntryDetails, EntryDownloadException?>, context: Context) = result.filter {
        it.value != null
    }.map {
        ErrorItem(
            it.key.displayName ?: "",
            context.getLocalizedString(R.string.form_details, it.key.formId ?: "", it.key.version ?: ""),
            EntryDownloadExceptionMapper(context).getMessage(it.value)
        )
    }

    fun getNumberOfFailures(result: Map<ServerEntryDetails, EntryDownloadException?>) = result.count {
        it.value != null
    }

    fun allEntriesDownloadedSuccessfully(result: Map<ServerEntryDetails, EntryDownloadException?>) = result.values.all {
        it == null
    }
}
