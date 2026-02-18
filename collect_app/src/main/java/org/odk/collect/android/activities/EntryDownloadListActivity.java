package org.odk.collect.android.activities;

import android.app.ProgressDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.util.SparseBooleanArray;
import android.view.View;
import android.widget.AdapterView;
import android.widget.Button;
import android.widget.ListView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.lifecycle.ViewModelProvider;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import org.odk.collect.android.R;
import org.odk.collect.android.activities.viewmodels.EntryDownloadListViewModel;
import org.odk.collect.android.adapters.EntryDownloadListAdapter;
import org.odk.collect.android.entrymanagement.EntryDownloadException;
import org.odk.collect.android.entrymanagement.EntryDownloader;
import org.odk.collect.android.entrymanagement.EntrySourceExceptionMapper;
import org.odk.collect.android.entrymanagement.RefreshEntryListDialogFragment;
import org.odk.collect.android.entrymanagement.ServerEntriesDetailsFetcher;
import org.odk.collect.android.entrymanagement.ServerEntryDetails;
import org.odk.collect.android.formlists.sorting.FormListSortingOption;
import org.odk.collect.android.fragments.dialogs.EntriesDownloadResultDialog;
import org.odk.collect.android.injection.DaggerUtils;
import org.odk.collect.android.listeners.DownloadEntriesTaskListener;
import org.odk.collect.android.listeners.EntryListDownloaderListener;
import org.odk.collect.android.openrosa.HttpCredentialsInterface;
import org.odk.collect.android.tasks.DownloadEntriesTask;
import org.odk.collect.android.tasks.DownloadEntryListTask;
import org.odk.collect.android.utilities.ApplicationConstants;
import org.odk.collect.android.utilities.AuthDialogUtility;
import org.odk.collect.android.utilities.DialogUtils;
import org.odk.collect.android.utilities.WebCredentialsUtils;
import org.odk.collect.android.views.DayNightProgressDialog;
import org.odk.collect.androidshared.network.NetworkStateProvider;
import org.odk.collect.androidshared.ui.DialogFragmentUtils;
import org.odk.collect.androidshared.ui.ToastUtils;
import org.odk.collect.forms.entries.EntrySourceException;

import java.io.Serializable;
import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import javax.inject.Inject;

import timber.log.Timber;

public class EntryDownloadListActivity extends FormListActivity implements EntryListDownloaderListener,
        DownloadEntriesTaskListener, AuthDialogUtility.AuthDialogUtilityResultListener,
        AdapterView.OnItemClickListener, RefreshEntryListDialogFragment.RefreshEntryListDialogFragmentListener,
        EntriesDownloadResultDialog.EntryDownloadResultDialogListener {

    private static final String ENTRY_DOWNLOAD_LIST_SORTING_ORDER = "entryDownloadListSortingOrder";

    public static final String DISPLAY_ONLY_UPDATED_ENTRIES = "displayOnlyUpdatedEntries";
    private static final String BUNDLE_SELECTED_COUNT = "selectedcount";

    public static final String ENTRYFORM_ID_KEY = "entryformid";
    private static final String ENTRYFORM_VERSION_KEY = "entryformversion";
    public static final String ENTRYID_DISPLAY = "entryiddisplay";
    public static final String ENTRY_INSTANCE_ID = "instanceid";

    public static final String ENTRYNAME = "entryname";
    private static final String ENTRYDETAIL_KEY = "entrydetailkey";

    private AlertDialog alertDialog;
    private ProgressDialog cancelDialog;
    private Button downloadButton;

    private DownloadEntryListTask downloadEntryListTask;
    private DownloadEntriesTask downloadEntriesTask;
    private Button toggleButton;

    private final ArrayList<HashMap<String, String>> filteredEntryList = new ArrayList<>();

    private static final boolean DO_NOT_EXIT = false;

    private boolean displayOnlyUpdatedEntries;

    private EntryDownloadListViewModel viewModel;

    @Inject
    WebCredentialsUtils webCredentialsUtils;

    @Inject
    ServerEntriesDetailsFetcher serverEntriesDetailsFetcher;

    @Inject
    NetworkStateProvider connectivityProvider;

    @Inject
    EntryDownloader entryDownloader;

    @SuppressWarnings("unchecked")
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        DaggerUtils.getComponent(this).inject(this);

        setContentView(R.layout.entry_download_list);
        setTitle(getString(R.string.get_entries_with_issues));

        viewModel = new ViewModelProvider(this, new EntryDownloadListViewModel.Factory())
                .get(EntryDownloadListViewModel.class);

        init(savedInstanceState);
    }

    private void init(Bundle savedInstanceState) {
        Bundle bundle = getIntent().getExtras();
        if (bundle != null) {
            if (bundle.containsKey(DISPLAY_ONLY_UPDATED_ENTRIES)) {
                displayOnlyUpdatedEntries = (boolean) bundle.get(DISPLAY_ONLY_UPDATED_ENTRIES);
            }

            if (bundle.containsKey(ApplicationConstants.BundleKeys.INSTANCE_IDS)) {
                viewModel.setDownloadOnlyMode(true);
                viewModel.setInstanceIdsToDownload(bundle.getStringArray(ApplicationConstants.BundleKeys.INSTANCE_IDS));

                if (viewModel.getInstanceIdsToDownload() == null) {
                    setReturnResult(false, "Instance Ids is null", null);
                    finish();
                }

                if (bundle.containsKey(ApplicationConstants.BundleKeys.URL)) {
                    viewModel.setUrl(bundle.getString(ApplicationConstants.BundleKeys.URL));

                    if (bundle.containsKey(ApplicationConstants.BundleKeys.USERNAME)
                            && bundle.containsKey(ApplicationConstants.BundleKeys.PASSWORD)) {
                        viewModel.setUsername(bundle.getString(ApplicationConstants.BundleKeys.USERNAME));
                        viewModel.setPassword(bundle.getString(ApplicationConstants.BundleKeys.PASSWORD));
                    }
                }
            }
        }

        downloadButton = findViewById(R.id.add_button);
        downloadButton.setEnabled(listView.getCheckedItemCount() > 0);
        downloadButton.setOnClickListener(v -> {
            ArrayList<ServerEntryDetails> filesToDownload = getFilesToDownload();
            startEntriesDownload(filesToDownload);
        });

        toggleButton = findViewById(R.id.toggle_button);
        toggleButton.setEnabled(false);
        toggleButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                downloadButton.setEnabled(toggleChecked(listView));
                toggleButtonLabel(toggleButton, listView);
                viewModel.clearSelectedInstanceIds();
                if (listView.getCheckedItemCount() == listView.getCount()) {
                    for (HashMap<String, String> map : viewModel.getEntryList()) {
                        viewModel.addSelectedInstanceId(map.get(ENTRYDETAIL_KEY));
                    }
                }
            }
        });

        Button refreshButton = findViewById(R.id.refresh_button);
        refreshButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                viewModel.setLoadingCanceled(false);
                viewModel.clearEntryList();
                updateAdapter();
                clearChoices();
                downloadEntryList();
            }
        });

        if (savedInstanceState != null) {
            // how many items we've selected
            // Android should keep track of this, but broken on rotate...
            if (savedInstanceState.containsKey(BUNDLE_SELECTED_COUNT)) {
                downloadButton.setEnabled(savedInstanceState.getInt(BUNDLE_SELECTED_COUNT) > 0);
            }
        }

        filteredEntryList.addAll(viewModel.getEntryList());

        if (getLastCustomNonConfigurationInstance() instanceof DownloadEntryListTask) {
            downloadEntryListTask = (DownloadEntryListTask) getLastCustomNonConfigurationInstance();
            if (downloadEntryListTask.getStatus() == AsyncTask.Status.FINISHED) {
                DialogFragmentUtils.dismissDialog(RefreshEntryListDialogFragment.class, getSupportFragmentManager());
                downloadEntriesTask = null;
            }
        } else if (getLastCustomNonConfigurationInstance() instanceof DownloadEntriesTask) {
            downloadEntriesTask = (DownloadEntriesTask) getLastCustomNonConfigurationInstance();
            if (downloadEntriesTask.getStatus() == AsyncTask.Status.FINISHED) {
                DialogFragmentUtils.dismissDialog(RefreshEntryListDialogFragment.class, getSupportFragmentManager());
                downloadEntriesTask = null;
            }
        } else if (viewModel.getEntryDetailsByInstanceId().isEmpty()
                && getLastCustomNonConfigurationInstance() == null
                && !viewModel.wasLoadingCanceled()) {
            // first time, so get the entrieslist
            downloadEntryList();
        }

        listView.setChoiceMode(ListView.CHOICE_MODE_MULTIPLE);
        listView.setItemsCanFocus(false);

        sortingOptions = Arrays.asList(
                new FormListSortingOption(
                        R.drawable.ic_sort_by_alpha,
                        R.string.sort_by_name_asc
                ),
                new FormListSortingOption(
                        R.drawable.ic_sort_by_alpha,
                        R.string.sort_by_name_desc
                )
        );
    }

    private void clearChoices() {
        listView.clearChoices();
        downloadButton.setEnabled(false);
    }

    @Override
    public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
        toggleButtonLabel(toggleButton, listView);
        downloadButton.setEnabled(listView.getCheckedItemCount() > 0);

        if (listView.isItemChecked(position)) {
            viewModel.addSelectedInstanceId(((HashMap<String, String>) listView.getAdapter().getItem(position)).get(ENTRYDETAIL_KEY));
        } else {
            viewModel.removeSelectedInstanceId(((HashMap<String, String>) listView.getAdapter().getItem(position)).get(ENTRYDETAIL_KEY));
        }
    }

    /**
     * Starts the download task and shows the progress dialog.
     */
    private void downloadEntryList() {
        if (!connectivityProvider.isDeviceOnline()) {
            ToastUtils.showShortToast(this, R.string.no_connection);

            if (viewModel.isDownloadOnlyMode()) {
                setReturnResult(false, getString(R.string.no_connection), viewModel.getEntryResults());
                finish();
            }
        } else {
            viewModel.clearEntryDetailsByInstanceId();
            DialogFragmentUtils.showIfNotShowing(RefreshEntryListDialogFragment.class, getSupportFragmentManager());

            if (downloadEntryListTask != null
                    && downloadEntryListTask.getStatus() != AsyncTask.Status.FINISHED) {
                return; // we are already doing the download!!!
            } else if (downloadEntryListTask != null) {
                downloadEntryListTask.setDownloaderListener(null);
                downloadEntryListTask.cancel(true);
                downloadEntryListTask = null;
            }

            if (viewModel.isDownloadOnlyMode()) {
                // Handle external app download case with different server
                downloadEntryListTask = new DownloadEntryListTask(serverEntriesDetailsFetcher);
                downloadEntryListTask.setAlternateCredentials(webCredentialsUtils, viewModel.getUrl(), viewModel.getUsername(), viewModel.getPassword());
                downloadEntryListTask.setDownloaderListener(this);
                downloadEntryListTask.execute();
            } else {
                downloadEntryListTask = new DownloadEntryListTask(serverEntriesDetailsFetcher);
                downloadEntryListTask.setDownloaderListener(this);
                downloadEntryListTask.execute();
            }
        }
    }

    @Override
    protected void onRestoreInstanceState(Bundle state) {
        super.onRestoreInstanceState(state);
        updateAdapter();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putInt(BUNDLE_SELECTED_COUNT, listView.getCheckedItemCount());
    }

    @Override
    protected String getSortingOrderKey() {
        return ENTRY_DOWNLOAD_LIST_SORTING_ORDER;
    }

    @Override
    protected void updateAdapter() {
        CharSequence charSequence = getFilterText();
        filteredEntryList.clear();
        if (charSequence.length() > 0) {
            for (HashMap<String, String> entry : viewModel.getEntryList()) {
                if (entry.get(ENTRYNAME).toLowerCase(Locale.US).contains(charSequence.toString().toLowerCase(Locale.US))) {
                    filteredEntryList.add(entry);
                }
            }
        } else {
            filteredEntryList.addAll(viewModel.getEntryList());
        }
        sortList();
        if (listView.getAdapter() == null) {
            listView.setAdapter(new EntryDownloadListAdapter(this, filteredEntryList, viewModel.getEntryDetailsByInstanceId()));
        } else {
            EntryDownloadListAdapter entryDownloadListAdapter = (EntryDownloadListAdapter) listView.getAdapter();
            entryDownloadListAdapter.setFromIdsToDetails(viewModel.getEntryDetailsByInstanceId());
            entryDownloadListAdapter.notifyDataSetChanged();
        }
        toggleButton.setEnabled(!filteredEntryList.isEmpty());
        checkPreviouslyCheckedItems();
        toggleButtonLabel(toggleButton, listView);
    }

    @Override
    protected void checkPreviouslyCheckedItems() {
        for (int i = 0; i < listView.getCount(); i++) {
            HashMap<String, String> item =
                    (HashMap<String, String>) listView.getAdapter().getItem(i);
            if (viewModel.getSelectedInstanceIds().contains(item.get(ENTRYDETAIL_KEY))) {
                listView.setItemChecked(i, true);
            }
        }
    }

    private void sortList() {
        Collections.sort(filteredEntryList, new Comparator<HashMap<String, String>>() {
            @Override
            public int compare(HashMap<String, String> lhs, HashMap<String, String> rhs) {
                if (getSortingOrder().equals(SORT_BY_NAME_ASC)) {
                    return lhs.get(ENTRYNAME).compareToIgnoreCase(rhs.get(ENTRYNAME));
                } else {
                    return rhs.get(ENTRYNAME).compareToIgnoreCase(lhs.get(ENTRYNAME));
                }
            }
        });
    }

    private ArrayList<ServerEntryDetails> getFilesToDownload() {
        ArrayList<ServerEntryDetails> filesToDownload = new ArrayList<>();

        SparseBooleanArray sba = listView.getCheckedItemPositions();
        for (int i = 0; i < listView.getCount(); i++) {
            if (sba.get(i, false)) {
                HashMap<String, String> item =
                        (HashMap<String, String>) listView.getAdapter().getItem(i);
                filesToDownload.add(viewModel.getEntryDetailsByInstanceId().get(item.get(ENTRYDETAIL_KEY)));
            }
        }
        return filesToDownload;
    }

    /**
     * starts the task to download the selected entries, also shows progress dialog
     */
    @SuppressWarnings("unchecked")
    private void startEntriesDownload(@NonNull ArrayList<ServerEntryDetails> filesToDownload) {
        int totalCount = filesToDownload.size();
        if (totalCount > 0) {
            // show dialog box
            DialogFragmentUtils.showIfNotShowing(RefreshEntryListDialogFragment.class, getSupportFragmentManager());

            downloadEntriesTask = new DownloadEntriesTask(entryDownloader);
            downloadEntriesTask.setDownloaderListener(this);

            if (viewModel.getUrl() != null) {
                if (viewModel.getUsername() != null && viewModel.getPassword() != null) {
                    webCredentialsUtils.saveCredentials(viewModel.getUrl(), viewModel.getUsername(), viewModel.getPassword());
                } else {
                    webCredentialsUtils.clearCredentials(viewModel.getUrl());
                }
            }

            downloadEntriesTask.execute(filesToDownload);
        } else {
            ToastUtils.showShortToast(this, R.string.noselect_error);
        }
    }

    @Override
    public Object onRetainCustomNonConfigurationInstance() {
        if (downloadEntriesTask != null) {
            return downloadEntriesTask;
        } else {
            return downloadEntryListTask;
        }
    }

    @Override
    protected void onDestroy() {
        if (downloadEntryListTask != null) {
            downloadEntryListTask.setDownloaderListener(null);
        }
        if (downloadEntriesTask != null) {
            downloadEntriesTask.setDownloaderListener(null);
        }
        super.onDestroy();
    }

    @Override
    protected void onResume() {
        if (downloadEntryListTask != null) {
            downloadEntryListTask.setDownloaderListener(this);
        }
        if (downloadEntriesTask != null) {
            downloadEntriesTask.setDownloaderListener(this);
        }
        if (viewModel.isAlertShowing()) {
            createAlertDialog(viewModel.getAlertTitle(), viewModel.getAlertDialogMsg(), viewModel.shouldExit());
        }
        if (viewModel.isCancelDialogShowing()) {
            createCancelDialog();
        }
        super.onResume();
    }

    @Override
    protected void onPause() {
        if (alertDialog != null && alertDialog.isShowing()) {
            alertDialog.dismiss();
        }
        super.onPause();
    }

    public boolean isLocalEntrySuperseded(String instanceId) {
        if (instanceId == null) {
            Timber.e(new Error("isLocalEntrySuperseded: server is not OpenRosa-compliant. <instanceID> is null!"));
            return true;
        }

        ServerEntryDetails entry = viewModel.getEntryDetailsByInstanceId().get(instanceId);
        return (entry != null && (entry.isNotOnDevice() || entry.isUpdated()));
    }

    /**
     * Causes any local entries that have been updated on the server to become checked in the list.
     * This is a prompt and a
     * convenience to users to download the latest version of those entries from the server.
     */
    private void selectSupersededEntries() {
        ListView ls = listView;
        for (int idx = 0; idx < filteredEntryList.size(); idx++) {
            HashMap<String, String> item = filteredEntryList.get(idx);
            if (isLocalEntrySuperseded(item.get(ENTRY_INSTANCE_ID))) {
                ls.setItemChecked(idx, true);
                viewModel.addSelectedInstanceId(item.get(ENTRYDETAIL_KEY));
            }
        }
    }

    @Override
    public void entryListDownloadingComplete(HashMap<String, ServerEntryDetails> entryList, EntrySourceException exception) {
        DialogFragmentUtils.dismissDialog(RefreshEntryListDialogFragment.class, getSupportFragmentManager());
        downloadEntryListTask.setDownloaderListener(null);
        downloadEntryListTask = null;

        if (exception == null) {
            // Everything worked. Clear the list and add the results.
            viewModel.setEntryDetailsByInstanceId(entryList);
            viewModel.clearEntryList();

            ArrayList<String> ids = new ArrayList<>(viewModel.getEntryDetailsByInstanceId().keySet());
            for (int i = 0; i < entryList.size(); i++) {
                String entryDetailsKey = ids.get(i);
                ServerEntryDetails details = viewModel.getEntryDetailsByInstanceId().get(entryDetailsKey);

                if (!displayOnlyUpdatedEntries || details.isUpdated()) {
                    HashMap<String, String> item = new HashMap<>();


                    item.put(ENTRYNAME, details.getDisplayName());
                    item.put(ENTRYID_DISPLAY,
                            ((details.getVersion() == null) ? "" : (getString(R.string.version) + " "
                                    + details.getVersion() + " ")) + "ID: " + details.getFormId());
                    item.put(ENTRYDETAIL_KEY, entryDetailsKey);
                    item.put(ENTRYFORM_ID_KEY, details.getFormId());
                    item.put(ENTRYFORM_VERSION_KEY, details.getVersion());
                    item.put(ENTRY_INSTANCE_ID, details.getInstanceId());

                    // Insert the new entry in alphabetical order.
                    if (viewModel.getEntryList().isEmpty()) {
                        viewModel.addEntry(item);
                    } else {
                        int j;
                        for (j = 0; j < viewModel.getEntryList().size(); j++) {
                            HashMap<String, String> compareMe = viewModel.getEntryList().get(j);
                            String name = compareMe.get(ENTRYNAME);
                            if (name.compareTo(viewModel.getEntryDetailsByInstanceId().get(ids.get(i)).getDisplayName()) > 0) {
                                break;
                            }
                        }
                        viewModel.addEntry(j, item);
                    }
                }
            }

            filteredEntryList.addAll(viewModel.getEntryList());
            updateAdapter();
            selectSupersededEntries();
            downloadButton.setEnabled(listView.getCheckedItemCount() > 0);
            toggleButton.setEnabled(listView.getCount() > 0);
            toggleButtonLabel(toggleButton, listView);

            if (viewModel.isDownloadOnlyMode()) {
                performDownloadModeDownload();
            }
        } else {
            if (exception instanceof EntrySourceException.AuthRequired) {
                createAuthDialog();
            } else {
                String dialogMessage = new EntrySourceExceptionMapper(this).getMessage(exception);
                String dialogTitle = getString(R.string.load_remote_entry_error);

                if (viewModel.isDownloadOnlyMode()) {
                    setReturnResult(false, dialogMessage, viewModel.getEntryResults());
                }

                createAlertDialog(dialogTitle, dialogMessage, DO_NOT_EXIT);
            }
        }
    }

    private void performDownloadModeDownload() {
        //1. First check if all instance IDS could be found on the server - Register entries that could not be found

        for (String instanceId : viewModel.getInstanceIdsToDownload()) {
            viewModel.putEntryResult(instanceId, false);
        }

        ArrayList<ServerEntryDetails> filesToDownload = new ArrayList<>();

        for (ServerEntryDetails serverEntryDetails : viewModel.getEntryDetailsByInstanceId().values()) {
            String instanceId = serverEntryDetails.getInstanceId();

            if (viewModel.getEntryResults().containsKey(instanceId)) {
                filesToDownload.add(serverEntryDetails);
            }
        }

        //2. Select entries and start downloading
        if (!filesToDownload.isEmpty()) {
            startEntriesDownload(filesToDownload);
        } else {
            // None of the entries was found
            setReturnResult(false, "Entries not found on server", viewModel.getEntryResults());
            finish();
        }
    }

    /**
     * Creates an alert dialog with the given tite and message. If shouldExit is set to true, the
     * activity will exit when the user clicks "ok".
     */
    private void createAlertDialog(String title, String message, final boolean shouldExit) {
        alertDialog = new MaterialAlertDialogBuilder(this).create();
        alertDialog.setTitle(title);
        alertDialog.setMessage(message);
        DialogInterface.OnClickListener quitListener = new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int i) {
                switch (i) {
                    case DialogInterface.BUTTON_POSITIVE: // ok
                        // just close the dialog
                        viewModel.setAlertShowing(false);
                        // successful download, so quit
                        // Also quit if in download_mode only(called by another app/activity just to download)
                        if (shouldExit || viewModel.isDownloadOnlyMode()) {
                            finish();
                        }
                        break;
                }
            }
        };
        alertDialog.setCancelable(false);
        alertDialog.setButton(AlertDialog.BUTTON_POSITIVE, getString(R.string.ok), quitListener);
        viewModel.setAlertDialogMsg(message);
        viewModel.setAlertTitle(title);
        viewModel.setAlertShowing(true);
        viewModel.setShouldExit(shouldExit);
        DialogUtils.showDialog(alertDialog, this);
    }

    private void createAuthDialog() {
        viewModel.setAlertShowing(false);

        AuthDialogUtility authDialogUtility = new AuthDialogUtility();
        if (viewModel.getUrl() != null && viewModel.getUsername() != null && viewModel.getPassword() != null) {
            authDialogUtility.setCustomUsername(viewModel.getUsername());
            authDialogUtility.setCustomPassword(viewModel.getPassword());
        }
        DialogUtils.showDialog(authDialogUtility.createDialog(this, this, viewModel.getUrl()), this);
    }

    private void createCancelDialog() {
        cancelDialog = new DayNightProgressDialog(this);
        cancelDialog.setTitle(getString(R.string.canceling));
        cancelDialog.setMessage(getString(R.string.please_wait));
        cancelDialog.setIndeterminate(true);
        cancelDialog.setCancelable(false);
        viewModel.setCancelDialogShowing(true);
        DialogUtils.showDialog(cancelDialog, this);
    }

    @Override
    public void progressUpdate(String currentFile, int progress, int total) {
        RefreshEntryListDialogFragment fragment = (RefreshEntryListDialogFragment) getSupportFragmentManager().findFragmentByTag(RefreshEntryListDialogFragment.class.getName());

        if (fragment != null) {
            fragment.setMessage(getString(R.string.fetching_entry_file, currentFile,
                    String.valueOf(progress), String.valueOf(total)));
        }
    }

    @Override
    public void entriesDownloadingComplete(Map<ServerEntryDetails, EntryDownloadException> result) {
        if (downloadEntriesTask != null) {
            downloadEntriesTask.setDownloaderListener(null);
        }

        cleanUpWebCredentials();

        DialogFragmentUtils.dismissDialog(RefreshEntryListDialogFragment.class, getSupportFragmentManager());

        Bundle args = new Bundle();
        args.putSerializable(EntriesDownloadResultDialog.ARG_RESULT, (Serializable) result);
        DialogFragmentUtils.showIfNotShowing(EntriesDownloadResultDialog.class, args, getSupportFragmentManager());

        // Set result to true for entries which were downloaded
        if (viewModel.isDownloadOnlyMode()) {
            for (ServerEntryDetails serverEntryDetails : result.keySet()) {
                if (result.get(serverEntryDetails) == null) {
                    if (viewModel.getEntryResults().containsKey(serverEntryDetails.getInstanceId())) {
                        viewModel.putEntryResult(serverEntryDetails.getInstanceId(), true);
                    }
                }
            }

            setReturnResult(true, null, viewModel.getEntryResults());
        }
    }

    @Override
    public void entriesDownloadingCancelled() {
        if (downloadEntriesTask != null) {
            downloadEntriesTask.setDownloaderListener(null);
            downloadEntriesTask = null;
        }

        cleanUpWebCredentials();

        if (cancelDialog != null && cancelDialog.isShowing()) {
            cancelDialog.dismiss();
            viewModel.setCancelDialogShowing(false);
        }

        if (viewModel.isDownloadOnlyMode()) {
            setReturnResult(false, "Download cancelled", null);
            finish();
        }
    }

    @Override
    public void updatedCredentials() {
        // If the user updated the custom credentials using the dialog, let us update our
        // variables holding the custom credentials
        if (viewModel.getUrl() != null) {
            HttpCredentialsInterface httpCredentials = webCredentialsUtils.getCredentials(URI.create(viewModel.getUrl()));

            if (httpCredentials != null) {
                viewModel.setUsername(httpCredentials.getUsername());
                viewModel.setPassword(httpCredentials.getPassword());
            }
        }

        downloadEntryList();
    }

    @Override
    public void cancelledUpdatingCredentials() {
        finish();
    }

    private void setReturnResult(boolean successful, @Nullable String message, @Nullable HashMap<String, Boolean> resultEntryIds) {
        Intent intent = new Intent();
        intent.putExtra(ApplicationConstants.BundleKeys.SUCCESS_KEY, successful);
        if (message != null) {
            intent.putExtra(ApplicationConstants.BundleKeys.MESSAGE, message);
        }
        if (resultEntryIds != null) {
            intent.putExtra(ApplicationConstants.BundleKeys.ENTRY_IDS, resultEntryIds);
        }

        setResult(RESULT_OK, intent);
    }

    private void cleanUpWebCredentials() {
        if (viewModel.getUrl() != null) {
            String host = Uri.parse(viewModel.getUrl())
                    .getHost();

            if (host != null) {
                webCredentialsUtils.clearCredentials(viewModel.getUrl());
            }
        }
    }

    @Override
    public void onCancelEntryLoading() {
        if (downloadEntryListTask != null) {
            downloadEntryListTask.setDownloaderListener(null);
            downloadEntryListTask.cancel(true);
            downloadEntryListTask = null;

            // Only explicitly exit if DownloadEntryListTask is running since
            // DownloadEntryTask has a callback when cancelled and has code to handle
            // cancellation when in download mode only
            if (viewModel.isDownloadOnlyMode()) {
                setReturnResult(false, "User cancelled the operation", viewModel.getEntryResults());
                finish();
            }
        }

        if (downloadEntriesTask != null) {
            createCancelDialog();
            downloadEntriesTask.cancel(true);
        }
        viewModel.setLoadingCanceled(true);
    }

    @Override
    public void onCloseDownloadingResult() {
        finish();
    }

}
