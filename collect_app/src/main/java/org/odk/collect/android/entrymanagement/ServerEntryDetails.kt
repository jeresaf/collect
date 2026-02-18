package org.odk.collect.android.entrymanagement

import java.io.Serializable

data class ServerEntryDetails(
    val displayName: String,
    val formType: String,
    val issue: String,
    val start: String,
    val end: String,
    val date: String?,
    val deviceId: String,
    val instanceId: String,
    val downloadUrl: String,
    val formId: String,
    val version: String,
    val hash: String?,
    val isNotOnDevice: Boolean,
    val isUpdated: Boolean
) : Serializable {

    companion object {
        private const val serialVersionUID = 1L
    }

}