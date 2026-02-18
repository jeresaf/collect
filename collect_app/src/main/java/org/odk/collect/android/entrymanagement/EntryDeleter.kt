package org.odk.collect.android.entrymanagement

import org.odk.collect.forms.FormsRepository
import org.odk.collect.forms.entries.EntriesRepository
import org.odk.collect.forms.entries.Entry

class EntryDeleter(
    private val entriesRepository: EntriesRepository,
    private val formsRepository: FormsRepository
) {
    fun delete(id: Long?) {
        entriesRepository[id]?.let { entry ->
            if (entry.status == Entry.STATUS_SUBMITTED) {
                entriesRepository.deleteWithLogging(id)
            } else {
                entriesRepository.delete(id)
            }
            val form =
                formsRepository.getLatestByFormIdAndVersion(entry.formId, entry.formVersion)
            if (form != null && form.isDeleted) {
                val otherEntries = entriesRepository.getAllNotDeletedByFormIdAndVersion(
                    form.formId,
                    form.version
                )
                if (otherEntries.isEmpty()) {
                    formsRepository.delete(form.dbId)
                }
            }
        }
    }
}
