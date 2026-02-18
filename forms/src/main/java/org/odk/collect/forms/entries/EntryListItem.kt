package org.odk.collect.forms.entries

data class EntryListItem(
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
    val hash: String?
)
