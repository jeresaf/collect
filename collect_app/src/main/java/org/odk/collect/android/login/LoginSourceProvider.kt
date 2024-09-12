package org.odk.collect.android.login

import org.odk.collect.android.openrosa.OpenRosaFormSource
import org.odk.collect.android.openrosa.OpenRosaHttpInterface
import org.odk.collect.android.openrosa.OpenRosaLoginSource
import org.odk.collect.android.openrosa.OpenRosaResponseParserImpl
import org.odk.collect.android.openrosa.OpenRosaUserHttpInterface
import org.odk.collect.android.openrosa.OpenRosaUserResponseParserImpl
import org.odk.collect.android.utilities.WebCredentialsUtils
import org.odk.collect.forms.FormSource
import org.odk.collect.settings.SettingsProvider
import org.odk.collect.settings.keys.ProjectKeys

class LoginSourceProvider(
    private val settingsProvider: SettingsProvider,
    private val openRosaUserHttpInterface: OpenRosaUserHttpInterface
) {

    @JvmOverloads
    fun get(projectId: String? = null): LoginSource {
        val generalSettings = settingsProvider.getUnprotectedSettings(projectId)

        val serverURL = generalSettings.getString(ProjectKeys.KEY_SERVER_URL)

        return OpenRosaLoginSource(
            serverURL,
            WebCredentialsUtils(generalSettings),
            openRosaUserHttpInterface,
            OpenRosaUserResponseParserImpl()
        )
    }
}
