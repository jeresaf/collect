package org.odk.collect.android.database.entries

import android.provider.BaseColumns

/**
 * Columns for the Forms table.
 */
object DatabaseEntryColumns : BaseColumns {

    // instance column names
    const val DISPLAY_NAME = "displayName"
    const val FORM_TYPE = "formType"
    const val ISSUE = "issue"
    const val START = "startDate"
    const val END = "endDate"
    const val DATE = "submissionDate"
    const val DEVICE_ID = "deviceId"
    const val INSTANCE_ID = "instanceId"
    const val ENTRY_FILE_PATH = "entryFilePath"
    const val MD5_HASH = "md5Hash"
    const val JR_FORM_ID = "jrFormId"
    const val JR_VERSION = "jrVersion"
    const val STATUS = "status"
    const val DELETED_DATE = "deletedDate"

}
