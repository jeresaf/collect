package org.odk.collect.android.entrymanagement

import org.odk.collect.android.formmanagement.FormDownloader.ProgressReporter
import java.util.function.Supplier

interface EntryDownloader {

    @Throws(EntryDownloadException::class)
    fun downloadEntry(
        form: ServerEntryDetails?,
        isCancelled: Supplier<Boolean?>?
    )
}
