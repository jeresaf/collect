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

import static org.odk.collect.strings.localization.LocalizedApplicationKt.getLocalizedString;
import static java.util.Collections.emptyMap;

import android.os.AsyncTask;

import org.odk.collect.android.R;
import org.odk.collect.android.application.Collect;
import org.odk.collect.android.entrymanagement.EntryDownloadException;
import org.odk.collect.android.entrymanagement.EntryDownloader;
import org.odk.collect.android.entrymanagement.ServerEntryDetails;
import org.odk.collect.android.formmanagement.FormDownloadException;
import org.odk.collect.android.formmanagement.FormDownloader;
import org.odk.collect.android.formmanagement.ServerFormDetails;
import org.odk.collect.android.listeners.DownloadEntriesTaskListener;
import org.odk.collect.android.listeners.DownloadFormsTaskListener;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

/**
 * Background task for downloading a given list of forms. We assume right now that the forms are
 * coming from the same server that presented the form list, but theoretically that won't always be
 * true.
 *
 * @author msundt
 * @author carlhartung
 */
public class DownloadEntriesTask extends
        AsyncTask<ArrayList<ServerEntryDetails>, String, Map<ServerEntryDetails, EntryDownloadException>> {

    private final EntryDownloader entryDownloader;
    private DownloadEntriesTaskListener stateListener;

    public DownloadEntriesTask(EntryDownloader entryDownloader) {
        this.entryDownloader = entryDownloader;
    }

    @Override
    protected Map<ServerEntryDetails, EntryDownloadException> doInBackground(ArrayList<ServerEntryDetails>... values) {
        HashMap<ServerEntryDetails, EntryDownloadException> results = new HashMap<>();

        int index = 1;
        for (ServerEntryDetails serverEntryDetails : values[0]) {
            try {
                String currentFormNumber = String.valueOf(index);
                String totalForms = String.valueOf(values[0].size());
                publishProgress(serverEntryDetails.getDisplayName(), currentFormNumber, totalForms);

                entryDownloader.downloadEntry(serverEntryDetails, this::isCancelled);

                results.put(serverEntryDetails, null);
            } catch (EntryDownloadException.DownloadingInterrupted e) {
                return emptyMap();
            } catch (EntryDownloadException e) {
                results.put(serverEntryDetails, e);
            }

            index++;
        }

        return results;
    }

    @Override
    protected void onCancelled(Map<ServerEntryDetails, EntryDownloadException> entryDetailsStringHashMap) {
        synchronized (this) {
            if (stateListener != null) {
                stateListener.entriesDownloadingCancelled();
            }
        }
    }

    @Override
    protected void onPostExecute(Map<ServerEntryDetails, EntryDownloadException> value) {
        synchronized (this) {
            if (stateListener != null) {
                stateListener.entriesDownloadingComplete(value);
            }
        }
    }

    @Override
    protected void onProgressUpdate(String... values) {
        synchronized (this) {
            if (stateListener != null) {
                // update progress and total
                stateListener.progressUpdate(values[0],
                        Integer.parseInt(values[1]),
                        Integer.parseInt(values[2]));
            }
        }

    }

    public void setDownloaderListener(DownloadEntriesTaskListener sl) {
        synchronized (this) {
            stateListener = sl;
        }
    }
}
