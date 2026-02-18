package org.odk.collect.android.entrymanagement

import org.odk.collect.forms.FormSourceException
import org.odk.collect.forms.entries.EntrySourceException

sealed class EntryDownloadException : Exception() {
    class DownloadingInterrupted : EntryDownloadException()
    class EntryWithNoHash : EntryDownloadException()
    class EntryParsingError : EntryDownloadException()
    class DiskError : EntryDownloadException()
    class InvalidSubmission : EntryDownloadException()
    class EntrySourceError(val exception: EntrySourceException) : EntryDownloadException()
}
