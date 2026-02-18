/*
 * Copyright 2018 Nafundi
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.odk.collect.android.entrymanagement

import org.odk.collect.android.openrosa.OpenRosaEntrySource
import org.odk.collect.android.openrosa.OpenRosaFormSource
import org.odk.collect.android.utilities.FormUtils
import org.odk.collect.android.utilities.WebCredentialsUtils
import org.odk.collect.forms.Form
import org.odk.collect.forms.FormSource
import org.odk.collect.forms.FormSourceException
import org.odk.collect.forms.FormsRepository
import org.odk.collect.forms.ManifestFile
import org.odk.collect.forms.MediaFile
import org.odk.collect.forms.entries.EntriesRepository
import org.odk.collect.forms.entries.Entry
import org.odk.collect.forms.entries.EntrySource
import org.odk.collect.forms.entries.EntrySourceException
import org.odk.collect.settings.SettingsProvider
import org.odk.collect.settings.keys.ProjectKeys.KEY_METADATA_PHONENUMBER
import org.odk.collect.shared.strings.Md5.getMd5Hash
import timber.log.Timber
import java.io.File
import javax.inject.Inject

/**
 * Open to allow mocking (used in existing Java tests)
 */
open class ServerEntriesDetailsFetcher(
    private val entriesRepository: EntriesRepository,
    private val entrySource: EntrySource,
    private val settingsProvider: SettingsProvider
) {
    open fun updateUrl(url: String) {
        (entrySource as OpenRosaEntrySource).updateUrl(url)
    }

    open fun updateCredentials(webCredentialsUtils: WebCredentialsUtils) {
        (entrySource as OpenRosaEntrySource).updateWebCredentialsUtils(webCredentialsUtils)
    }

    @Throws(EntrySourceException::class)
    open fun fetchEntryDetails(): List<ServerEntryDetails> {
        val username = settingsProvider.getUnprotectedSettings().getString(KEY_METADATA_PHONENUMBER);
        val entryList = entrySource.fetchEntryListURL(username)
        return entryList.map { listItem ->

            val entries = entriesRepository.getAllByInstanceId(listItem.instanceId)
            val thisEntryAlreadyDownloaded = entries.isNotEmpty()
            val isNewerEntryVersionAvailable = listItem.hash.let {
                if (it == null) {
                    false
                } else if (thisEntryAlreadyDownloaded) {
                    val existingEntry = getEntryByHash(it)
                    if (existingEntry == null || existingEntry.isDeleted) {
                        true
                    }else {
                        false
                    }
                } else {
                    false
                }
            }

            ServerEntryDetails(
                listItem.displayName,
                listItem.formType,
                listItem.issue,
                listItem.start,
                listItem.end,
                listItem.date,
                listItem.deviceId,
                listItem.instanceId,
                listItem.downloadUrl,
                listItem.formId,
                listItem.version,
                listItem.hash,
                !thisEntryAlreadyDownloaded,
                isNewerEntryVersionAvailable,
            )
        }
    }

    private fun getEntryByHash(hash: String): Entry? {
        return entriesRepository.getOneByMd5Hash(hash)
    }
}
