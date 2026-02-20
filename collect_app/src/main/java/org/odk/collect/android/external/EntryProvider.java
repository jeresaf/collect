/*
 * Copyright (C) 2007 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */

package org.odk.collect.android.external;

import static org.odk.collect.android.database.DatabaseObjectMapper.getEntryFromCurrentCursorPosition;
import static org.odk.collect.android.database.DatabaseObjectMapper.getEntryFromValues;
import static org.odk.collect.android.database.DatabaseObjectMapper.getValuesFromEntry;
import static org.odk.collect.android.database.entries.DatabaseEntryColumns._ID;
import static org.odk.collect.android.external.EntriesContract.CONTENT_ITEM_TYPE;
import static org.odk.collect.android.external.EntriesContract.CONTENT_TYPE;
import static org.odk.collect.android.external.EntriesContract.getUri;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.UriMatcher;
import android.content.res.Resources;
import android.database.Cursor;
import android.net.Uri;

import androidx.annotation.NonNull;

import org.odk.collect.android.R;
import org.odk.collect.android.analytics.AnalyticsEvents;
import org.odk.collect.android.analytics.AnalyticsUtils;
import org.odk.collect.android.dao.CursorLoaderFactory;
import org.odk.collect.android.database.entries.DatabaseEntriesRepository;
import org.odk.collect.android.entrymanagement.EntryDeleter;
import org.odk.collect.android.injection.DaggerUtils;
import org.odk.collect.android.storage.StoragePathProvider;
import org.odk.collect.android.storage.StorageSubdirectory;
import org.odk.collect.android.utilities.ContentUriHelper;
import org.odk.collect.android.utilities.EntriesRepositoryProvider;
import org.odk.collect.android.utilities.FormsRepositoryProvider;
import org.odk.collect.forms.entries.EntriesRepository;
import org.odk.collect.forms.entries.Entry;
import org.odk.collect.projects.ProjectsRepository;
import org.odk.collect.settings.SettingsProvider;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

import javax.inject.Inject;

import timber.log.Timber;

public class EntryProvider extends ContentProvider {

    private static final int ENTRIES = 1;
    private static final int ENTRY_ID = 2;
    private static final UriMatcher URI_MATCHER = new UriMatcher(UriMatcher.NO_MATCH);

    //@Inject
    //InstancesRepositoryProvider instancesRepositoryProvider;

    @Inject
    FormsRepositoryProvider formsRepositoryProvider;

    @Inject
    EntriesRepositoryProvider entriesRepositoryProvider;

    @Inject
    StoragePathProvider storagePathProvider;

    @Inject
    ProjectsRepository projectsRepository;

    @Inject
    SettingsProvider settingsProvider;

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Cursor query(@NonNull Uri uri, String[] projection, String selection, String[] selectionArgs,
                        String sortOrder) {
        DaggerUtils.getComponent(getContext()).inject(this);

        String projectId = getProjectId(uri);

        // We only want to log external calls to the content provider
        if (uri.getQueryParameter(CursorLoaderFactory.INTERNAL_QUERY_PARAM) == null) {
            logServerEvent(projectId, AnalyticsEvents.ENTRY_PROVIDER_QUERY);
        }

        Cursor c;
        switch (URI_MATCHER.match(uri)) {
            case ENTRIES:
                c = dbQuery(projectId, projection, selection, selectionArgs, sortOrder);
                break;

            case ENTRY_ID:
                String id = String.valueOf(ContentUriHelper.getIdFromUri(uri));
                c = dbQuery(projectId, projection, _ID + "=?", new String[]{id}, null);
                break;

            default:
                throw new IllegalArgumentException("Unknown URI " + uri);
        }

        // Tell the cursor what uri to watch, so it knows when its source data changes
        c.setNotificationUri(getContext().getContentResolver(), uri);
        return c;
    }

    private Cursor dbQuery(String projectId, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        return ((DatabaseEntriesRepository) entriesRepositoryProvider.get(projectId)).rawQuery(projection, selection, selectionArgs, sortOrder, null);
    }

    @Override
    public String getType(@NonNull Uri uri) {
        switch (URI_MATCHER.match(uri)) {
            case ENTRIES:
                return CONTENT_TYPE;

            case ENTRY_ID:
                return CONTENT_ITEM_TYPE;

            default:
                throw new IllegalArgumentException("Unknown URI " + uri);
        }
    }

    @Override
    public Uri insert(@NonNull Uri uri, ContentValues initialValues) {
        DaggerUtils.getComponent(getContext()).inject(this);

        String projectId = getProjectId(uri);
        logServerEvent(projectId, AnalyticsEvents.ENTRY_PROVIDER_INSERT);

        // Validate the requested uri
        if (URI_MATCHER.match(uri) != ENTRIES) {
            throw new IllegalArgumentException("Unknown URI " + uri);
        }

        Entry newEntry = entriesRepositoryProvider.get(projectId).save(getEntryFromValues(initialValues));
        return getUri(projectId, newEntry.getDbId());
    }

    public static String getDisplaySubtext(Context context, String state, Date date) {
        return getDisplaySubtext(context.getResources(), state, date);
    }

    public static String getDisplaySubtext(Resources resources, String state, Date date) {
        try {
            Timber.e("getDisplaySubtext");
            if (state == null) {
                return new SimpleDateFormat(resources.getString(R.string.added_on_date_at_time),
                        Locale.getDefault()).format(date);
            } else if (Entry.STATUS_INCOMPLETE.equalsIgnoreCase(state)) {
                return new SimpleDateFormat(resources.getString(R.string.saved_on_date_at_time),
                        Locale.getDefault()).format(date);
            } else if (Entry.STATUS_COMPLETE.equalsIgnoreCase(state)) {
                return new SimpleDateFormat(resources.getString(R.string.finalized_on_date_at_time),
                        Locale.getDefault()).format(date);
            } else if (Entry.STATUS_SUBMITTED.equalsIgnoreCase(state)) {
                return new SimpleDateFormat(resources.getString(R.string.sent_on_date_at_time),
                        Locale.getDefault()).format(date);
            } else if (Entry.STATUS_SUBMISSION_FAILED.equalsIgnoreCase(state)) {
                return new SimpleDateFormat(
                        resources.getString(R.string.sending_failed_on_date_at_time),
                        Locale.getDefault()).format(date);
            } else {
                return new SimpleDateFormat(resources.getString(R.string.added_on_date_at_time),
                        Locale.getDefault()).format(date);
            }
        } catch (IllegalArgumentException e) {
            Timber.e(e, "Current locale: %s", Locale.getDefault());
            return "";
        }
    }

    /**
     * This method removes the entry from the content provider, and also removes any associated
     * files.
     * files:  form.xml, [formmd5].formdef, formname-media {directory}
     */
    @Override
    public int delete(@NonNull Uri uri, String where, String[] whereArgs) {
        DaggerUtils.getComponent(getContext()).inject(this);

        String projectId = getProjectId(uri);
        logServerEvent(projectId, AnalyticsEvents.ENTRY_PROVIDER_DELETE);

        int count;

        switch (URI_MATCHER.match(uri)) {
            case ENTRIES:
                try (Cursor cursor = dbQuery(projectId, new String[]{_ID}, where, whereArgs, null)) {
                    while (cursor.moveToNext()) {
                        long id = cursor.getLong(cursor.getColumnIndex(_ID));
                        new EntryDeleter(entriesRepositoryProvider.get(projectId), formsRepositoryProvider.get(projectId)).delete(id);
                    }

                    count = cursor.getCount();
                }

                break;

            case ENTRY_ID:
                long id = ContentUriHelper.getIdFromUri(uri);

                if (where == null) {
                    new EntryDeleter(entriesRepositoryProvider.get(projectId), formsRepositoryProvider.get(projectId)).delete(id);
                } else {
                    try (Cursor cursor = dbQuery(projectId, new String[]{_ID}, where, whereArgs, null)) {
                        while (cursor.moveToNext()) {
                            if (cursor.getLong(cursor.getColumnIndex(_ID)) == id) {
                                new EntryDeleter(entriesRepositoryProvider.get(), formsRepositoryProvider.get()).delete(id);
                                break;
                            }
                        }
                    }
                }

                count = 1;
                break;

            default:
                throw new IllegalArgumentException("Unknown URI " + uri);
        }

        getContext().getContentResolver().notifyChange(uri, null);

        return count;
    }

    @Override
    public int update(@NonNull Uri uri, ContentValues values, String where, String[] whereArgs) {
        DaggerUtils.getComponent(getContext()).inject(this);

        String projectId = getProjectId(uri);
        logServerEvent(projectId, AnalyticsEvents.ENTRY_PROVIDER_UPDATE);

        EntriesRepository entriesRepository = entriesRepositoryProvider.get(projectId);
        String entriesPath = storagePathProvider.getOdkDirPath(StorageSubdirectory.ENTRIES, projectId);

        int count;

        switch (URI_MATCHER.match(uri)) {
            case ENTRIES:
                try (Cursor cursor = dbQuery(projectId, null, where, whereArgs, null)) {
                    while (cursor.moveToNext()) {
                        Entry entry = getEntryFromCurrentCursorPosition(cursor, entriesPath);
                        ContentValues existingValues = getValuesFromEntry(entry, entriesPath);

                        existingValues.putAll(values);
                        Entry updatedEntry = getEntryFromValues(existingValues);
                        if (Entry.STATUS_SUBMITTED.equals(updatedEntry.getStatus())) {
                            new EntryDeleter(entriesRepositoryProvider.get(projectId), formsRepositoryProvider.get(projectId)).delete(updatedEntry.getDbId());
                        } else {
                            entriesRepository.save(updatedEntry);
                        }
                    }

                    count = cursor.getCount();
                }

                break;

            case ENTRY_ID:
                long entryId = ContentUriHelper.getIdFromUri(uri);
                if (whereArgs == null || whereArgs.length == 0) {
                    Entry entry = entriesRepository.get(entryId);
                    ContentValues existingValues = getValuesFromEntry(entry, entriesPath);

                    existingValues.putAll(values);
                    Entry updatedEntry = getEntryFromValues(existingValues);
                    if (Entry.STATUS_SUBMITTED.equals(updatedEntry.getStatus())) {
                        new EntryDeleter(entriesRepositoryProvider.get(projectId), formsRepositoryProvider.get(projectId)).delete(updatedEntry.getDbId());
                    } else {
                        entriesRepository.save(updatedEntry);
                    }
                    count = 1;
                } else {
                    try (Cursor cursor = dbQuery(projectId, new String[]{_ID}, where, whereArgs, null)) {
                        while (cursor.moveToNext()) {
                            if (cursor.getLong(cursor.getColumnIndex(_ID)) == entryId) {
                                Entry entry = getEntryFromCurrentCursorPosition(cursor, entriesPath);
                                ContentValues existingValues = getValuesFromEntry(entry, entriesPath);

                                existingValues.putAll(values);
                                Entry updatedEntry = getEntryFromValues(existingValues);
                                if (Entry.STATUS_SUBMITTED.equals(updatedEntry.getStatus())) {
                                    new EntryDeleter(entriesRepositoryProvider.get(projectId), formsRepositoryProvider.get(projectId)).delete(updatedEntry.getDbId());
                                } else {
                                    entriesRepository.save(updatedEntry);
                                }
                                break;
                            }
                        }
                    }

                    count = 1;
                }

                break;

            default:
                throw new IllegalArgumentException("Unknown URI " + uri);
        }

        getContext().getContentResolver().notifyChange(uri, null);

        return count;
    }

    private String getProjectId(@NonNull Uri uri) {
        String queryParam = uri.getQueryParameter("projectId");

        if (queryParam != null) {
            return queryParam;
        } else {
            return projectsRepository.getAll().get(0).getUuid();
        }
    }

    private void logServerEvent(String projectId, String event) {
        AnalyticsUtils.logServerEvent(event, settingsProvider.getUnprotectedSettings(projectId));
    }

    static {
        URI_MATCHER.addURI(EntriesContract.AUTHORITY, "entries", ENTRIES);
        URI_MATCHER.addURI(EntriesContract.AUTHORITY, "entries/#", ENTRY_ID);
    }
}
