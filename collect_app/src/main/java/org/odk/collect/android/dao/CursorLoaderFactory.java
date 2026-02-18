package org.odk.collect.android.dao;

import android.net.Uri;

import androidx.loader.content.CursorLoader;

import org.odk.collect.android.application.Collect;
import org.odk.collect.android.database.entries.DatabaseEntryColumns;
import org.odk.collect.android.database.instances.DatabaseInstanceColumns;
import org.odk.collect.android.external.EntriesContract;
import org.odk.collect.android.external.InstancesContract;
import org.odk.collect.android.projects.CurrentProjectProvider;
import org.odk.collect.forms.entries.Entry;
import org.odk.collect.forms.instances.Instance;

import timber.log.Timber;

@Deprecated
public class CursorLoaderFactory {

    public static final String INTERNAL_QUERY_PARAM = "internal";
    private final CurrentProjectProvider currentProjectProvider;

    public CursorLoaderFactory(CurrentProjectProvider currentProjectProvider) {
        this.currentProjectProvider = currentProjectProvider;
    }

    public CursorLoader createSentInstancesCursorLoader(CharSequence charSequence, String sortOrder) {
        CursorLoader cursorLoader;
        if (charSequence.length() == 0) {
            String selection = DatabaseInstanceColumns.STATUS + "=? or " + DatabaseInstanceColumns.STATUS + "=?";
            String[] selectionArgs = {Instance.STATUS_SUBMITTED, Instance.STATUS_SUBMISSION_FAILED};

            cursorLoader = getInstancesCursorLoader(selection, selectionArgs, sortOrder);
        } else {
            String selection =
                    "(" + DatabaseInstanceColumns.STATUS + "=? or "
                            + DatabaseInstanceColumns.STATUS + "=?) and "
                            + DatabaseInstanceColumns.DISPLAY_NAME + " LIKE ?";
            String[] selectionArgs = {
                    Instance.STATUS_SUBMITTED,
                    Instance.STATUS_SUBMISSION_FAILED,
                    "%" + charSequence + "%"};

            cursorLoader = getInstancesCursorLoader(selection, selectionArgs, sortOrder);
        }

        return cursorLoader;
    }

    public CursorLoader createEditableInstancesCursorLoader(CharSequence charSequence, String sortOrder) {
        CursorLoader cursorLoader;
        if (charSequence.length() == 0) {
            String selection = DatabaseInstanceColumns.STATUS + " =? ";
            String[] selectionArgs = {Instance.STATUS_INCOMPLETE};

            cursorLoader = getInstancesCursorLoader(selection, selectionArgs, sortOrder);
        } else {
            String selection = DatabaseInstanceColumns.STATUS + " =? " +
                    "and " + DatabaseInstanceColumns.DISPLAY_NAME + " LIKE ?";
            String[] selectionArgs = {
                    Instance.STATUS_INCOMPLETE,
                    "%" + charSequence + "%"
            };

            cursorLoader = getInstancesCursorLoader(selection, selectionArgs, sortOrder);
        }

        return cursorLoader;
    }

    public CursorLoader createSavedInstancesCursorLoader(CharSequence charSequence, String sortOrder) {
        CursorLoader cursorLoader;
        if (charSequence.length() == 0) {
            String selection = DatabaseInstanceColumns.DELETED_DATE + " IS NULL ";
            cursorLoader = getInstancesCursorLoader(selection, null, sortOrder);
        } else {
            String selection =
                    DatabaseInstanceColumns.DELETED_DATE + " IS NULL and "
                            + DatabaseInstanceColumns.DISPLAY_NAME + " LIKE ?";
            String[] selectionArgs = {"%" + charSequence + "%"};
            cursorLoader = getInstancesCursorLoader(selection, selectionArgs, sortOrder);
        }

        return cursorLoader;
    }

    public CursorLoader createFinalizedInstancesCursorLoader(CharSequence charSequence, String sortOrder) {
        CursorLoader cursorLoader;
        if (charSequence.length() == 0) {
            String selection = DatabaseInstanceColumns.STATUS + "=? or " + DatabaseInstanceColumns.STATUS + "=?";
            String[] selectionArgs = {Instance.STATUS_COMPLETE, Instance.STATUS_SUBMISSION_FAILED};

            cursorLoader = getInstancesCursorLoader(selection, selectionArgs, sortOrder);
        } else {
            String selection =
                    "(" + DatabaseInstanceColumns.STATUS + "=? or "
                            + DatabaseInstanceColumns.STATUS + "=?) and "
                            + DatabaseInstanceColumns.DISPLAY_NAME + " LIKE ?";
            String[] selectionArgs = {
                    Instance.STATUS_COMPLETE,
                    Instance.STATUS_SUBMISSION_FAILED,
                    "%" + charSequence + "%"};

            cursorLoader = getInstancesCursorLoader(selection, selectionArgs, sortOrder);
        }

        return cursorLoader;
    }

    public CursorLoader createCompletedUndeletedInstancesCursorLoader(CharSequence charSequence, String sortOrder) {
        CursorLoader cursorLoader;
        if (charSequence.length() == 0) {
            String selection = DatabaseInstanceColumns.DELETED_DATE + " IS NULL and ("
                    + DatabaseInstanceColumns.STATUS + "=? or "
                    + DatabaseInstanceColumns.STATUS + "=? or "
                    + DatabaseInstanceColumns.STATUS + "=?)";

            String[] selectionArgs = {Instance.STATUS_COMPLETE,
                    Instance.STATUS_SUBMISSION_FAILED,
                    Instance.STATUS_SUBMITTED};

            cursorLoader = getInstancesCursorLoader(selection, selectionArgs, sortOrder);
        } else {
            String selection = DatabaseInstanceColumns.DELETED_DATE + " IS NULL and ("
                    + DatabaseInstanceColumns.STATUS + "=? or "
                    + DatabaseInstanceColumns.STATUS + "=? or "
                    + DatabaseInstanceColumns.STATUS + "=?) and "
                    + DatabaseInstanceColumns.DISPLAY_NAME + " LIKE ?";

            String[] selectionArgs = {
                    Instance.STATUS_COMPLETE,
                    Instance.STATUS_SUBMISSION_FAILED,
                    Instance.STATUS_SUBMITTED,
                    "%" + charSequence + "%"};

            cursorLoader = getInstancesCursorLoader(selection, selectionArgs, sortOrder);
        }
        return cursorLoader;
    }

    private CursorLoader getInstancesCursorLoader(String selection, String[] selectionArgs, String sortOrder) {
        Uri uri = InstancesContract.getUri(currentProjectProvider.getCurrentProject().getUuid());

        return new CursorLoader(
                Collect.getInstance(),
                getUriWithAnalyticsParam(uri),
                null,
                selection,
                selectionArgs,
                sortOrder);
    }

    public CursorLoader createEntriesCursorLoader(CharSequence charSequence, String sortOrder) {
        CursorLoader cursorLoader;
        if (charSequence.length() == 0) {
            String selection = DatabaseEntryColumns.STATUS + " =? ";
            String[] selectionArgs = {Entry.STATUS_INCOMPLETE};

            cursorLoader = getEntriesCursorLoader(selection, selectionArgs, sortOrder);
        } else {
            String selection = DatabaseEntryColumns.STATUS + " =? " +
                    "and " + DatabaseEntryColumns.DISPLAY_NAME + " LIKE ?";
            String[] selectionArgs = {
                    Entry.STATUS_INCOMPLETE,
                    "%" + charSequence + "%"
            };

            cursorLoader = getEntriesCursorLoader(selection, selectionArgs, sortOrder);
        }

        return cursorLoader;
    }

    private CursorLoader getEntriesCursorLoader(String selection, String[] selectionArgs, String sortOrder) {
        Uri uri = EntriesContract.getUri(currentProjectProvider.getCurrentProject().getUuid());

        return new CursorLoader(
                Collect.getInstance(),
                getUriWithAnalyticsParam(uri),
                null,
                selection,
                selectionArgs,
                sortOrder);
    }

    private Uri getUriWithAnalyticsParam(Uri uri) {
        return uri.buildUpon()
                .appendQueryParameter(INTERNAL_QUERY_PARAM, "true")
                .build();
    }
}
