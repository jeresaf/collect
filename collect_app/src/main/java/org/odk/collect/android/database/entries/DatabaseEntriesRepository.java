package org.odk.collect.android.database.entries;

import static android.provider.BaseColumns._ID;
import static org.odk.collect.android.database.DatabaseConstants.ENTRIES_TABLE_NAME;
import static org.odk.collect.android.database.DatabaseConstants.FORMS_TABLE_NAME;
import static org.odk.collect.android.database.DatabaseConstants.INSTANCES_TABLE_NAME;
import static org.odk.collect.android.database.DatabaseObjectMapper.getEntryFromCurrentCursorPosition;
import static org.odk.collect.android.database.DatabaseObjectMapper.getInstanceFromCurrentCursorPosition;
import static org.odk.collect.android.database.DatabaseObjectMapper.getValuesFromEntry;
import static org.odk.collect.android.database.DatabaseObjectMapper.getValuesFromInstance;
import static org.odk.collect.android.database.entries.DatabaseEntryColumns.DATE;
import static org.odk.collect.android.database.entries.DatabaseEntryColumns.DEVICE_ID;
import static org.odk.collect.android.database.entries.DatabaseEntryColumns.END;
import static org.odk.collect.android.database.entries.DatabaseEntryColumns.ENTRY_FILE_PATH;
import static org.odk.collect.android.database.entries.DatabaseEntryColumns.FORM_TYPE;
import static org.odk.collect.android.database.entries.DatabaseEntryColumns.INSTANCE_ID;
import static org.odk.collect.android.database.entries.DatabaseEntryColumns.ISSUE;
import static org.odk.collect.android.database.entries.DatabaseEntryColumns.MD5_HASH;
import static org.odk.collect.android.database.entries.DatabaseEntryColumns.START;
import static org.odk.collect.android.database.forms.DatabaseFormColumns.FORM_FILE_PATH;
import static org.odk.collect.android.database.instances.DatabaseInstanceColumns.CAN_EDIT_WHEN_COMPLETE;
import static org.odk.collect.android.database.instances.DatabaseInstanceColumns.DELETED_DATE;
import static org.odk.collect.android.database.instances.DatabaseInstanceColumns.DISPLAY_NAME;
import static org.odk.collect.android.database.instances.DatabaseInstanceColumns.GEOMETRY;
import static org.odk.collect.android.database.instances.DatabaseInstanceColumns.GEOMETRY_TYPE;
import static org.odk.collect.android.database.instances.DatabaseInstanceColumns.INSTANCE_FILE_PATH;
import static org.odk.collect.android.database.instances.DatabaseInstanceColumns.JR_FORM_ID;
import static org.odk.collect.android.database.instances.DatabaseInstanceColumns.JR_VERSION;
import static org.odk.collect.android.database.instances.DatabaseInstanceColumns.LAST_STATUS_CHANGE_DATE;
import static org.odk.collect.android.database.instances.DatabaseInstanceColumns.STATUS;
import static org.odk.collect.android.database.instances.DatabaseInstanceColumns.SUBMISSION_URI;
import static org.odk.collect.shared.PathUtils.getRelativeFilePath;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteQueryBuilder;

import org.jetbrains.annotations.NotNull;
import org.odk.collect.android.database.DatabaseConnection;
import org.odk.collect.android.database.DatabaseConstants;
import org.odk.collect.android.database.forms.DatabaseFormColumns;
import org.odk.collect.android.database.instances.InstanceDatabaseMigrator;
import org.odk.collect.forms.Form;
import org.odk.collect.forms.entries.EntriesRepository;
import org.odk.collect.forms.entries.Entry;
import org.odk.collect.forms.instances.Instance;
import org.odk.collect.forms.instances.InstancesRepository;
import org.odk.collect.shared.files.DirectoryUtils;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import javax.annotation.Nullable;

/**
 * Mediates between {@link Instance} objects and the underlying SQLite database that stores them.
 */
public final class DatabaseEntriesRepository implements EntriesRepository {

    private final DatabaseConnection databaseConnection;
    private final Supplier<Long> clock;
    private final String entriesPath;

    public DatabaseEntriesRepository(Context context, String dbPath, String entriesPath, Supplier<Long> clock) {
        this.databaseConnection = new DatabaseConnection(
                context,
                dbPath,
                DatabaseConstants.ENTRIES_DATABASE_NAME,
                new EntryDatabaseMigrator(),
                DatabaseConstants.ENTRIES_DATABASE_VERSION
        );

        this.clock = clock;
        this.entriesPath = entriesPath;
    }

    @Override
    public Entry get(Long databaseId) {
        String selection = _ID + "=?";
        String[] selectionArgs = {Long.toString(databaseId)};

        try (Cursor cursor = query(null, selection, selectionArgs, null)) {
            List<Entry> result = getEntriesFromCursor(cursor, entriesPath);
            return !result.isEmpty() ? result.get(0) : null;
        }
    }

    @Override
    public Entry getOneByPath(String path) {
        String selection = ENTRY_FILE_PATH + "=?";
        String[] selectionArgs = {getRelativeFilePath(entriesPath, path)};
        return queryForEntry(selection, selectionArgs);
    }

    @Nullable
    @Override
    public Entry getOneByMd5Hash(@NotNull String hash) {
        if (hash == null) {
            throw new IllegalArgumentException("Missing form hash. ODK-compatible servers must include form hashes in their form lists. Please talk to the person who asked you to collect data.");
        }

        String selection = MD5_HASH + "=?";
        String[] selectionArgs = {hash};
        return queryForEntry(selection, selectionArgs);
    }

    @Nullable
    @Override
    public Entry getOneByInstanceId(@NotNull String instanceId) {
        String selection = INSTANCE_ID + "=?";
        String[] selectionArgs = {instanceId};
        return queryForEntry(selection, selectionArgs);
    }

    @Override
    public List<Entry> getAll() {
        try (Cursor cursor = query(null, null, null, null)) {
            return getEntriesFromCursor(cursor, entriesPath);
        }
    }

    @Override
    public List<Entry> getAllByStatus(String... status) {
        try (Cursor entriesCursor = getCursorForAllByStatus(status)) {
            return getEntriesFromCursor(entriesCursor, entriesPath);
        }
    }

    @Override
    public int getCountByStatus(String... status) {
        try (Cursor cursorForAllByStatus = getCursorForAllByStatus(status)) {
            return cursorForAllByStatus.getCount();
        }
    }


    @Override
    public List<Entry> getAllByInstanceId(String instanceId) {
        try (Cursor c = query(null, INSTANCE_ID + " = ?", new String[]{instanceId}, null)) {
            return getEntriesFromCursor(c, entriesPath);
        }
    }

    @Override
    public List<Entry> getAllNotDeletedByFormIdAndVersion(String jrFormId, String jrVersion) {
        if (jrVersion != null) {
            try (Cursor cursor = query(null, JR_FORM_ID + " = ? AND " + JR_VERSION + " = ? AND " + DELETED_DATE + " IS NULL", new String[]{jrFormId, jrVersion}, null)) {
                return getEntriesFromCursor(cursor, entriesPath);
            }
        } else {
            try (Cursor cursor = query(null, JR_FORM_ID + " = ? AND " + JR_VERSION + " IS NULL AND " + DELETED_DATE + " IS NULL", new String[]{jrFormId}, null)) {
                return getEntriesFromCursor(cursor, entriesPath);
            }
        }
    }

    @Override
    public void delete(Long id) {
        Entry entry = get(id);

        databaseConnection.getWriteableDatabase().delete(
                ENTRIES_TABLE_NAME,
                _ID + "=?",
                new String[]{String.valueOf(id)}
        );

        deleteEntryFiles(entry);
    }

    @Override
    public void deleteByMd5Hash(@NotNull String md5Hash) {
        String selection = MD5_HASH + "=?";
        String[] selectionArgs = {md5Hash};

        deleteEntries(selection, selectionArgs);
    }

    @Override
    public void deleteByInstanceId(@NotNull String instanceId) {
        String selection = INSTANCE_ID + "=?";
        String[] selectionArgs = {instanceId};

        deleteEntries(selection, selectionArgs);
    }

    @Override
    public void deleteAll() {
        List<Entry> entries = getAll();

        databaseConnection.getWriteableDatabase().delete(
                ENTRIES_TABLE_NAME,
                null,
                null
        );

        for (Entry entry : entries) {
            deleteEntryFiles(entry);
        }
    }

    @Override
    public void deleteWithLogging(Long id) {
        ContentValues values = new ContentValues();
        values.put(DELETED_DATE, clock.get());
        update(id, values);

        Entry entry = get(id);
        deleteEntryFiles(entry);
    }

    @Override
    public Entry save(Entry entry) {
        if (entry.getStatus() == null) {
            entry = new Entry.Builder(entry)
                    .status(Instance.STATUS_INCOMPLETE)
                    .build();
        }

        if (entry.getDbId() == null) {
            long insertId = insert(getValuesFromEntry(entry, entriesPath));
            return get(insertId);
        } else {
            if (entry.getDeletedDate() == null) {
                entry = new Entry.Builder(entry)
                        .build();
            }

            update(entry.getDbId(), getValuesFromEntry(entry, entriesPath));
            return get(entry.getDbId());
        }
    }

    public Cursor rawQuery(String[] projection, String selection, String[] selectionArgs, String sortOrder, String groupBy) {
        return query(projection, selection, selectionArgs, sortOrder);
    }

    @Nullable
    private Entry queryForEntry(String selection, String[] selectionArgs) {
        List<Entry> entries = queryForEntries(selection, selectionArgs);
        return !entries.isEmpty() ? entries.get(0) : null;
    }

    private List<Entry> queryForEntries(String selection, String[] selectionArgs) {
        try (Cursor cursor = queryAndReturnCursor(null, null, selection, selectionArgs, null, null)) {
            return getEntriesFromCursor(cursor, entriesPath);
        }
    }

    private Cursor queryAndReturnCursor(Map<String, String> projectionMap, String[] projection, String selection, String[] selectionArgs, String sortOrder, String groupBy) {
        SQLiteDatabase readableDatabase = databaseConnection.getReadableDatabase();
        SQLiteQueryBuilder qb = new SQLiteQueryBuilder();
        qb.setTables(ENTRIES_TABLE_NAME);

        if (projectionMap != null) {
            qb.setProjectionMap(projectionMap);
        }

        return qb.query(readableDatabase, projection, selection, selectionArgs, groupBy, null, sortOrder);
    }

    private Cursor getCursorForAllByStatus(String[] status) {
        StringBuilder selection = new StringBuilder(STATUS + "=?");
        for (int i = 1; i < status.length; i++) {
            selection.append(" or ").append(STATUS).append("=?");
        }

        return query(null, selection.toString(), status, null);
    }

    private Cursor query(String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        SQLiteDatabase readableDatabase = databaseConnection.getReadableDatabase();
        SQLiteQueryBuilder qb = new SQLiteQueryBuilder();
        qb.setTables(ENTRIES_TABLE_NAME);

        if (projection == null) {
            /*
             For some reason passing null as the projection doesn't always give us all the
             columns so we hardcode them here so it's explicit that we need these all back.
             */
            projection = new String[]{
                    _ID,
                    DISPLAY_NAME,
                    FORM_TYPE,
                    ISSUE,
                    START,
                    END,
                    DATE,
                    DEVICE_ID,
                    INSTANCE_ID,
                    ENTRY_FILE_PATH,
                    MD5_HASH,
                    JR_FORM_ID,
                    JR_VERSION,
                    STATUS,
                    DELETED_DATE
            };
        }

        return qb.query(readableDatabase, projection, selection, selectionArgs, null, null, sortOrder);
    }

    private long insert(ContentValues values) {
        return databaseConnection.getWriteableDatabase().insertOrThrow(
                ENTRIES_TABLE_NAME,
                null,
                values
        );
    }

    private void update(Long entryId, ContentValues values) {
        databaseConnection.getWriteableDatabase().update(
                ENTRIES_TABLE_NAME,
                values,
                _ID + "=?",
                new String[]{entryId.toString()}
        );
    }

    private void deleteEntries(String selection, String[] selectionArgs) {
        List<Entry> entries = queryForEntries(selection, selectionArgs);
        for (Entry entry : entries) {
            deleteEntryFiles(entry);
        }

        SQLiteDatabase writeableDatabase = databaseConnection.getWriteableDatabase();
        writeableDatabase.delete(ENTRIES_TABLE_NAME, selection, selectionArgs);
    }

    private void deleteEntryFiles(Entry entry) {
        DirectoryUtils.deleteDirectory(new File(entry.getEntryFilePath()).getParentFile());
    }

    private static List<Entry> getEntriesFromCursor(Cursor cursor, String entriesPath) {
        List<Entry> entries = new ArrayList<>();
        cursor.moveToPosition(-1);
        while (cursor.moveToNext()) {
            Entry entry = getEntryFromCurrentCursorPosition(cursor, entriesPath);
            entries.add(entry);
        }

        return entries;
    }
}
