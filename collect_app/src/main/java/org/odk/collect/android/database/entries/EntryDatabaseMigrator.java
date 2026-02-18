package org.odk.collect.android.database.entries;

import static android.provider.BaseColumns._ID;
import static org.odk.collect.android.database.DatabaseConstants.ENTRIES_TABLE_NAME;
import static org.odk.collect.android.database.entries.DatabaseEntryColumns.DATE;
import static org.odk.collect.android.database.entries.DatabaseEntryColumns.DELETED_DATE;
import static org.odk.collect.android.database.entries.DatabaseEntryColumns.DEVICE_ID;
import static org.odk.collect.android.database.entries.DatabaseEntryColumns.DISPLAY_NAME;
import static org.odk.collect.android.database.entries.DatabaseEntryColumns.END;
import static org.odk.collect.android.database.entries.DatabaseEntryColumns.ENTRY_FILE_PATH;
import static org.odk.collect.android.database.entries.DatabaseEntryColumns.FORM_TYPE;
import static org.odk.collect.android.database.entries.DatabaseEntryColumns.INSTANCE_ID;
import static org.odk.collect.android.database.entries.DatabaseEntryColumns.ISSUE;
import static org.odk.collect.android.database.entries.DatabaseEntryColumns.JR_FORM_ID;
import static org.odk.collect.android.database.entries.DatabaseEntryColumns.JR_VERSION;
import static org.odk.collect.android.database.entries.DatabaseEntryColumns.MD5_HASH;
import static org.odk.collect.android.database.entries.DatabaseEntryColumns.START;
import static org.odk.collect.android.database.entries.DatabaseEntryColumns.STATUS;

import android.database.sqlite.SQLiteDatabase;

import org.odk.collect.android.database.DatabaseMigrator;

import timber.log.Timber;

public class EntryDatabaseMigrator implements DatabaseMigrator {

    public void onCreate(SQLiteDatabase db) {
        createEntriesTableV1(db);
    }

    @SuppressWarnings({"checkstyle:FallThrough"})
    public void onUpgrade(SQLiteDatabase db, int oldVersion) {
        Timber.w("Entries db upgrade from version: %s", oldVersion);
        switch (oldVersion) {
            case 1:
                // Remember to bump the database version number in {@link org.odk.collect.android.database.DatabaseConstants}
                // upgradeToVersion2(db);
            default:
                Timber.i("Unknown version %d", oldVersion);
        }
    }

    public void onDowngrade(SQLiteDatabase db) {

    }

    private void createEntriesTableV1(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS " + ENTRIES_TABLE_NAME + " ("
                + _ID + " integer primary key, "
                + DISPLAY_NAME + " text not null, "
                + FORM_TYPE + " text not null, "
                + ISSUE + " longtext not null, "
                + START + " text not null, "
                + END + " text not null, "
                + DATE + " text, "
                + DEVICE_ID + " text not null, "
                + INSTANCE_ID + " text not null, "
                + ENTRY_FILE_PATH + " text not null, "
                + MD5_HASH + " text not null UNIQUE ON CONFLICT IGNORE, "
                + JR_FORM_ID + " text not null, "
                + JR_VERSION + " text, "
                + STATUS + " text not null, "
                + DELETED_DATE + " date );");
    }
}
