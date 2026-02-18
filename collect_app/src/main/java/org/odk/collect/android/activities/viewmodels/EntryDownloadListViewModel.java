/*
 * Copyright 2019 Nafundi
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.odk.collect.android.activities.viewmodels;

import androidx.annotation.NonNull;
import androidx.lifecycle.ViewModel;
import androidx.lifecycle.ViewModelProvider;

import org.odk.collect.android.entrymanagement.ServerEntryDetails;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;

public class EntryDownloadListViewModel extends ViewModel {

    private HashMap<String, ServerEntryDetails> entryDetailsByInstanceId = new HashMap<>();

    /**
     * List of entries from the entryList response. The map acts like a DisplayableEntry object with
     * values for each component that shows up in the entry list UI. See
     * EntryDownloadListActivity.entryListDownloadingComplete for keys.
     */
    private final ArrayList<HashMap<String, String>> entryList = new ArrayList<>();

    private final LinkedHashSet<String> selectedInstanceIds = new LinkedHashSet<>();

    private String alertTitle;
    private String alertDialogMsg;

    private boolean alertShowing;
    private boolean cancelDialogShowing;
    private boolean shouldExit;
    private boolean loadingCanceled;

    // Variables used when the activity is called from an external app
    private boolean isDownloadOnlyMode;
    private String[] instanceIdsToDownload;
    private String url;
    private String username;
    private String password;
    private final HashMap<String, Boolean> entryResults = new HashMap<>();

    public HashMap<String, ServerEntryDetails> getEntryDetailsByInstanceId() {
        return entryDetailsByInstanceId;
    }

    public void setEntryDetailsByInstanceId(HashMap<String, ServerEntryDetails> entryDetailsByInstanceId) {
        this.entryDetailsByInstanceId = entryDetailsByInstanceId;
    }

    public void clearEntryDetailsByInstanceId() {
        entryDetailsByInstanceId.clear();
    }

    public String getAlertTitle() {
        return alertTitle;
    }

    public void setAlertTitle(String alertTitle) {
        this.alertTitle = alertTitle;
    }

    public String getAlertDialogMsg() {
        return alertDialogMsg;
    }

    public void setAlertDialogMsg(String alertDialogMsg) {
        this.alertDialogMsg = alertDialogMsg;
    }

    public boolean isAlertShowing() {
        return alertShowing;
    }

    public void setAlertShowing(boolean alertShowing) {
        this.alertShowing = alertShowing;
    }

    public boolean shouldExit() {
        return shouldExit;
    }

    public void setShouldExit(boolean shouldExit) {
        this.shouldExit = shouldExit;
    }

    public ArrayList<HashMap<String, String>> getEntryList() {
        return entryList;
    }

    public void clearEntryList() {
        entryList.clear();
    }

    public void addEntry(HashMap<String, String> item) {
        entryList.add(item);
    }

    public void addEntry(int index, HashMap<String, String> item) {
        entryList.add(index, item);
    }

    public LinkedHashSet<String> getSelectedInstanceIds() {
        return selectedInstanceIds;
    }

    public void addSelectedInstanceId(String selectedInstanceId) {
        selectedInstanceIds.add(selectedInstanceId);
    }

    public void removeSelectedInstanceId(String selectedInstanceId) {
        selectedInstanceIds.remove(selectedInstanceId);
    }

    public void clearSelectedInstanceIds() {
        selectedInstanceIds.clear();
    }

    public boolean isDownloadOnlyMode() {
        return isDownloadOnlyMode;
    }

    public void setDownloadOnlyMode(boolean downloadOnlyMode) {
        isDownloadOnlyMode = downloadOnlyMode;
    }

    public HashMap<String, Boolean> getEntryResults() {
        return entryResults;
    }

    public void putEntryResult(String instanceId, boolean result) {
        entryResults.put(instanceId, result);
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public String[] getInstanceIdsToDownload() {
        return Arrays.copyOf(instanceIdsToDownload, instanceIdsToDownload.length);
    }

    public void setInstanceIdsToDownload(String[] instanceIdsToDownload) {
        this.instanceIdsToDownload = instanceIdsToDownload;
    }

    public boolean isCancelDialogShowing() {
        return cancelDialogShowing;
    }

    public void setCancelDialogShowing(boolean cancelDialogShowing) {
        this.cancelDialogShowing = cancelDialogShowing;
    }

    public boolean wasLoadingCanceled() {
        return loadingCanceled;
    }

    public void setLoadingCanceled(boolean loadingCanceled) {
        this.loadingCanceled = loadingCanceled;
    }

    public static class Factory implements ViewModelProvider.Factory {

        @SuppressWarnings("unchecked")
        @NonNull
        @Override
        public <T extends ViewModel> T create(@NonNull Class<T> modelClass) {
            return (T) new EntryDownloadListViewModel();
        }
    }
}
