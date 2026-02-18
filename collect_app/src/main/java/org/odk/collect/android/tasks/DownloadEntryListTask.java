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

package org.odk.collect.android.tasks;

import android.net.Uri;
import android.os.AsyncTask;

import androidx.core.util.Pair;

import org.odk.collect.android.entrymanagement.ServerEntriesDetailsFetcher;
import org.odk.collect.android.entrymanagement.ServerEntryDetails;
import org.odk.collect.android.formmanagement.ServerFormDetails;
import org.odk.collect.android.formmanagement.ServerFormsDetailsFetcher;
import org.odk.collect.android.listeners.EntryListDownloaderListener;
import org.odk.collect.android.listeners.FormListDownloaderListener;
import org.odk.collect.android.utilities.WebCredentialsUtils;
import org.odk.collect.forms.FormSourceException;
import org.odk.collect.forms.entries.EntrySourceException;

import java.util.HashMap;
import java.util.List;

import timber.log.Timber;

/**
 * Background task for downloading forms from urls or a formlist from a url. We overload this task
 * a bit so that we don't have to keep track of two separate downloading tasks and it simplifies
 * interfaces. If LIST_URL is passed to doInBackground(), we fetch a form list. If a hashmap
 * containing form/url pairs is passed, we download those forms.
 *
 * @author carlhartung
 */
public class DownloadEntryListTask extends AsyncTask<Void, String, Pair<List<ServerEntryDetails>, EntrySourceException>> {

    private final ServerEntriesDetailsFetcher serverEntriesDetailsFetcher;

    private EntryListDownloaderListener stateListener;
    private WebCredentialsUtils webCredentialsUtils;
    private String url;
    private String username;
    private String password;

    public DownloadEntryListTask(ServerEntriesDetailsFetcher serverEntriesDetailsFetcher) {
        this.serverEntriesDetailsFetcher = serverEntriesDetailsFetcher;
    }

    @Override
    protected Pair<List<ServerEntryDetails>, EntrySourceException> doInBackground(Void... values) {
        if (webCredentialsUtils != null) {
            setTemporaryCredentials();
        }

        List<ServerEntryDetails> entryList = null;
        EntrySourceException exception = null;

        try {
            entryList = serverEntriesDetailsFetcher.fetchEntryDetails();
        } catch (EntrySourceException e) {
            exception = e;
        } finally {
            if (webCredentialsUtils != null) {
                clearTemporaryCredentials();
            }
        }

        return new Pair<>(entryList, exception);
    }

    @Override
    protected void onPostExecute(Pair<List<ServerEntryDetails>, EntrySourceException> result) {
        synchronized (this) {
            if (stateListener != null) {
                Timber.e("Result: %s", result.toString());
                if (result.first != null) {
                    HashMap<String, ServerEntryDetails> detailsHashMap = new HashMap<>();
                    for (ServerEntryDetails details : result.first) {
                        detailsHashMap.put(details.getFormId(), details);
                    }

                    stateListener.entryListDownloadingComplete(detailsHashMap, result.second);
                } else {
                    stateListener.entryListDownloadingComplete(null, result.second);
                }
            }
        }
    }

    public void setDownloaderListener(EntryListDownloaderListener sl) {
        synchronized (this) {
            stateListener = sl;
        }
    }

    public void setAlternateCredentials(WebCredentialsUtils webCredentialsUtils, String url, String username, String password) {
        this.webCredentialsUtils = webCredentialsUtils;
        serverEntriesDetailsFetcher.updateCredentials(webCredentialsUtils);

        this.url = url;
        if (url != null && !url.isEmpty()) {
            serverEntriesDetailsFetcher.updateUrl(url);
        }

        this.username = username;
        this.password = password;
    }

    private void setTemporaryCredentials() {
        if (url != null) {
            String host = Uri.parse(url).getHost();

            if (host != null) {
                if (username != null && password != null) {
                    webCredentialsUtils.saveCredentials(url, username, password);
                } else {
                    webCredentialsUtils.clearCredentials(url);
                }
            }
        }
    }

    private void clearTemporaryCredentials() {
        if (url != null) {
            String host = Uri.parse(url).getHost();

            if (host != null) {
                webCredentialsUtils.clearCredentials(url);
            }
        }
    }
}
