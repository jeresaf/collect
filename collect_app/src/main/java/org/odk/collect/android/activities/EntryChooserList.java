/*
 * Copyright (C) 2009 University of Washington
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

package org.odk.collect.android.activities;

import static org.odk.collect.android.utilities.ApplicationConstants.SortingOrder.BY_NAME_ASC;
import static org.odk.collect.android.utilities.ApplicationConstants.SortingOrder.BY_NAME_DESC;

import android.content.DialogInterface;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.AdapterView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.loader.app.LoaderManager;
import androidx.loader.content.Loader;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import org.odk.collect.android.R;
import org.odk.collect.android.adapters.EntryListCursorAdapter;
import org.odk.collect.android.analytics.AnalyticsEvents;
import org.odk.collect.android.analytics.AnalyticsUtils;
import org.odk.collect.android.dao.CursorLoaderFactory;
import org.odk.collect.android.database.entries.DatabaseEntryColumns;
import org.odk.collect.android.external.EntriesContract;
import org.odk.collect.android.external.FormUriActivity;
import org.odk.collect.android.formlists.sorting.FormListSortingOption;
import org.odk.collect.android.injection.DaggerUtils;
import org.odk.collect.android.projects.CurrentProjectProvider;
import org.odk.collect.android.utilities.ApplicationConstants;
import org.odk.collect.android.utilities.FormsRepositoryProvider;
import org.odk.collect.androidshared.ui.multiclicksafe.MultiClickGuard;
import org.odk.collect.forms.Form;
import org.odk.collect.forms.entries.Entry;

import java.util.Arrays;

import javax.inject.Inject;

import timber.log.Timber;

/**
 * Responsible for displaying all the valid entries in the entry directory.
 *
 * @author Yaw Anokwa (yanokwa@gmail.com)
 * @author Carl Hartung (carlhartung@gmail.com)
 */
public class EntryChooserList extends AppListActivity implements AdapterView.OnItemClickListener, LoaderManager.LoaderCallbacks<Cursor> {
    private static final String ENTRY_LIST_ACTIVITY_SORTING_ORDER = "entryListActivitySortingOrder";
    private static final String VIEW_SENT_FORM_SORTING_ORDER = "ViewSentFormSortingOrder";

    private static final boolean DO_NOT_EXIT = false;

    private boolean editMode;

    @Inject
    CurrentProjectProvider currentProjectProvider;

    @Inject
    FormsRepositoryProvider formsRepositoryProvider;

    private final ActivityResultLauncher<Intent> formLauncher = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
        setResult(RESULT_OK, result.getData());
        finish();
    });

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.entry_chooser_list);
        DaggerUtils.getComponent(this).inject(this);

        setTitle(getString(R.string.entries_with_issues));

        /*String formMode = getIntent().getStringExtra(ApplicationConstants.BundleKeys.FORM_MODE);
        if (formMode == null || ApplicationConstants.FormModes.EDIT_SAVED.equalsIgnoreCase(formMode)) {
            setTitle(getString(R.string.review_data));
            editMode = true;
        } else {
            setTitle(getString(R.string.view_sent_forms));
            ((TextView) findViewById(android.R.id.empty)).setText(R.string.no_items_display_sent_forms);
        }*/

        sortingOptions = Arrays.asList(
                new FormListSortingOption(
                        R.drawable.ic_sort_by_alpha,
                        R.string.sort_by_name_asc
                ),
                new FormListSortingOption(
                        R.drawable.ic_sort_by_alpha,
                        R.string.sort_by_name_desc
                ),
                new FormListSortingOption(
                        R.drawable.ic_access_time,
                        R.string.sort_by_date_desc
                ),
                new FormListSortingOption(
                        R.drawable.ic_access_time,
                        R.string.sort_by_date_asc
                )
        );

        init();
    }

    private void init() {
        setupAdapter();
        getSupportLoaderManager().initLoader(LOADER_ID, null, this);
    }

    /**
     * Stores the path of selected entry in the parent class and finishes.
     */
    @Override
    public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
        if (MultiClickGuard.allowClick(getClass().getName())) {
            if (view.isEnabled()) {
                Cursor c = (Cursor) listView.getAdapter().getItem(position);
                long entryId = c.getLong(c.getColumnIndex(DatabaseEntryColumns._ID));
                Uri entryUri = EntriesContract.getUri(currentProjectProvider.getCurrentProject().getUuid(), entryId);
                Timber.e("Entry URI: %s", entryUri.toString());
                String action = getIntent().getAction();
                if (Intent.ACTION_PICK.equals(action)) {
                    // caller is waiting on a picked form
                    setResult(RESULT_OK, new Intent().setData(entryUri));
                    finish();
                } else {
                    // the form can be edited if it is incomplete or if, when it was
                    // marked as complete, it was determined that it could be edited
                    // later.
                    String status = c.getString(c.getColumnIndex(DatabaseEntryColumns.STATUS));

                    boolean canEdit = status.equals(Entry.STATUS_INCOMPLETE);
                    if (!canEdit) {
                        createErrorDialog(getString(R.string.cannot_edit_completed_form),
                                DO_NOT_EXIT);
                        return;
                    }
                    // caller wants to view/edit a form, so launch FormFillingActivity
                    Intent parentIntent = this.getIntent();
                    String formMode = parentIntent.getStringExtra(ApplicationConstants.BundleKeys.FORM_MODE);
                    if (formMode == null || ApplicationConstants.FormModes.EDIT_SAVED.equalsIgnoreCase(formMode)) {
                        String issue = c.getString(c.getColumnIndex(DatabaseEntryColumns.ISSUE));
                        showEntryIssueDialog(issue, () -> {
                            logFormEdit(c);
                            launchForm(entryUri, ApplicationConstants.FormModes.EDIT_SAVED);
                        });
                    } else {
                        launchForm(entryUri, ApplicationConstants.FormModes.VIEW_SENT);
                        finish();
                    }
                }
            } else {
                TextView disabledCause = view.findViewById(R.id.form_subtitle2);
                Toast.makeText(this, disabledCause.getText(), Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void logFormEdit(Cursor cursor) {
        String status = cursor.getString(cursor.getColumnIndex(DatabaseEntryColumns.STATUS));
        String formId = cursor.getString(cursor.getColumnIndex(DatabaseEntryColumns.JR_FORM_ID));
        String version = cursor.getString(cursor.getColumnIndex(DatabaseEntryColumns.JR_VERSION));

        Form form = formsRepositoryProvider.get().getLatestByFormIdAndVersion(formId, version);
        String formTitle = form != null ? form.getDisplayName() : "";

        if (status.equals(Entry.STATUS_INCOMPLETE)) {
            AnalyticsUtils.logFormEvent(AnalyticsEvents.EDIT_NON_FINALIZED_FORM, formId, formTitle);
        } else if (status.equals(Entry.STATUS_COMPLETE)) {
            AnalyticsUtils.logFormEvent(AnalyticsEvents.EDIT_FINALIZED_FORM, formId, formTitle);
        }
    }

    private void setupAdapter() {
        String[] data = {DatabaseEntryColumns.DISPLAY_NAME, DatabaseEntryColumns.DELETED_DATE};
        int[] view = {R.id.form_title, R.id.form_subtitle2};



        boolean shouldCheckDisabled = !editMode;
        listAdapter = new EntryListCursorAdapter(
                this, R.layout.entry_chooser_list_item, null, data, view, shouldCheckDisabled);
        listView.setAdapter(listAdapter);
    }

    private void showEntryIssueDialog(String issue, Runnable onConfirm) {
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.entries_with_issues_reason)
                .setMessage(issue)
                .setPositiveButton(R.string.edit_form, (dialog, which) -> onConfirm.run())
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void launchForm(Uri entryUri, String formMode) {
        Intent intent = new Intent(this, FormUriActivity.class);
        intent.setAction(Intent.ACTION_EDIT);
        intent.setData(entryUri);
        intent.putExtra(ApplicationConstants.BundleKeys.FORM_MODE, formMode);

        if (ApplicationConstants.FormModes.EDIT_SAVED.equalsIgnoreCase(formMode)) {
            formLauncher.launch(intent);
        } else {
            startActivity(intent);
        }
    }

    @Override
    protected String getSortingOrderKey() {
        return editMode ? ENTRY_LIST_ACTIVITY_SORTING_ORDER : VIEW_SENT_FORM_SORTING_ORDER;
    }

    @Override
    protected void updateAdapter() {
        getSupportLoaderManager().restartLoader(LOADER_ID, null, this);
    }

    @NonNull
    @Override
    public Loader<Cursor> onCreateLoader(int id, Bundle args) {
        showProgressBar();
        return new CursorLoaderFactory(currentProjectProvider).createEntriesCursorLoader(getFilterText(), getSortingOrder());
        /*
        if (editMode) {
            return new CursorLoaderFactory(currentProjectProvider).createEditableInstancesCursorLoader(getFilterText(), getSortingOrder());
        } else {
            return new CursorLoaderFactory(currentProjectProvider).createSentInstancesCursorLoader(getFilterText(), getSortingOrder());
        }
         */
    }

    @Override
    public void onLoadFinished(@NonNull Loader<Cursor> loader, Cursor cursor) {
        hideProgressBarAndAllow();
        listAdapter.swapCursor(cursor);
    }

    @Override
    public void onLoaderReset(@NonNull Loader loader) {
        listAdapter.swapCursor(null);
    }

    private void createErrorDialog(String errorMsg, final boolean shouldExit) {
        AlertDialog alertDialog = new MaterialAlertDialogBuilder(this).create();
        alertDialog.setMessage(errorMsg);
        DialogInterface.OnClickListener errorListener = new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int i) {
                switch (i) {
                    case DialogInterface.BUTTON_POSITIVE:
                        if (shouldExit) {
                            finish();
                        }
                        break;
                }
            }
        };
        alertDialog.setCancelable(false);
        alertDialog.setButton(AlertDialog.BUTTON_POSITIVE, getString(R.string.ok), errorListener);
        alertDialog.show();
    }

    protected String getSortingOrder() {
        String sortingOrder = DatabaseEntryColumns.DISPLAY_NAME + " COLLATE NOCASE ASC, " + DatabaseEntryColumns.STATUS + " DESC";
        switch (getSelectedSortingOrder()) {
            case BY_NAME_ASC:
                sortingOrder = DatabaseEntryColumns.DISPLAY_NAME + " COLLATE NOCASE ASC, " + DatabaseEntryColumns.STATUS + " DESC";
                break;
            case BY_NAME_DESC:
                sortingOrder = DatabaseEntryColumns.DISPLAY_NAME + " COLLATE NOCASE DESC, " + DatabaseEntryColumns.STATUS + " DESC";
                break;
            /*case BY_DATE_ASC:
                sortingOrder = DatabaseEntryColumns.LAST_STATUS_CHANGE_DATE + " ASC";
                break;
            case BY_DATE_DESC:
                sortingOrder = DatabaseEntryColumns.LAST_STATUS_CHANGE_DATE + " DESC";
                break;*/
        }
        return sortingOrder;
    }
}
