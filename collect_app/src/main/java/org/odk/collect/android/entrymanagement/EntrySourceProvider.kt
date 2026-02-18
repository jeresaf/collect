package org.odk.collect.android.entrymanagement

import org.odk.collect.android.openrosa.OpenRosaEntrySource
import org.odk.collect.android.openrosa.OpenRosaFormSource
import org.odk.collect.android.openrosa.OpenRosaHttpInterface
import org.odk.collect.android.openrosa.OpenRosaResponseParserImpl
import org.odk.collect.android.utilities.WebCredentialsUtils
import org.odk.collect.forms.FormSource
import org.odk.collect.forms.entries.EntrySource
import org.odk.collect.settings.SettingsProvider
import org.odk.collect.settings.keys.ProjectKeys

class EntrySourceProvider(
    private val settingsProvider: SettingsProvider,
    private val openRosaHttpInterface: OpenRosaHttpInterface
) {

    @JvmOverloads
    fun get(projectId: String? = null): EntrySource {
        val generalSettings = settingsProvider.getUnprotectedSettings(projectId)

        val serverURL = generalSettings.getString(ProjectKeys.KEY_SERVER_URL)

        return OpenRosaEntrySource(
            serverURL,
            openRosaHttpInterface,
            WebCredentialsUtils(generalSettings),
            OpenRosaResponseParserImpl()
        )
    }
}
