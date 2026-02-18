package org.odk.collect.forms.entries;

import org.jetbrains.annotations.NotNull;
import org.odk.collect.forms.FormSourceException;

import java.io.InputStream;
import java.util.List;

public interface EntrySource {
    List<EntryListItem> fetchEntryListURL(String username) throws EntrySourceException;

    @NotNull
    InputStream fetchEntry(String entryURL) throws EntrySourceException;
}
