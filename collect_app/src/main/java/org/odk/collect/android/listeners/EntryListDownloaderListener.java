package org.odk.collect.android.listeners;

import org.odk.collect.android.entrymanagement.ServerEntryDetails;
import org.odk.collect.forms.entries.EntrySourceException;

import java.util.HashMap;

public interface EntryListDownloaderListener {
    void entryListDownloadingComplete(HashMap<String, ServerEntryDetails> formList, EntrySourceException exception);
}
