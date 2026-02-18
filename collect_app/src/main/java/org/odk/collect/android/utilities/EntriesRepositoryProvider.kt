package org.odk.collect.android.utilities

import android.content.Context
import org.odk.collect.android.database.entries.DatabaseEntriesRepository
import org.odk.collect.android.storage.StoragePathProvider
import org.odk.collect.android.storage.StorageSubdirectory
import org.odk.collect.forms.entries.EntriesRepository

class EntriesRepositoryProvider @JvmOverloads constructor(
    private val context: Context,
    private val storagePathProvider: StoragePathProvider = StoragePathProvider()
) {

    private val clock = { System.currentTimeMillis() }

    @JvmOverloads
    fun get(projectId: String? = null): EntriesRepository {
        val dbPath = storagePathProvider.getOdkDirPath(StorageSubdirectory.METADATA, projectId)
        val entriesPath = storagePathProvider.getOdkDirPath(StorageSubdirectory.ENTRIES, projectId)
        return DatabaseEntriesRepository(context, dbPath, entriesPath, clock)
    }
}
