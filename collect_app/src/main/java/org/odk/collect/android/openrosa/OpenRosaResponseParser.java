package org.odk.collect.android.openrosa;

import org.jetbrains.annotations.Nullable;
import org.kxml2.kdom.Document;
import org.odk.collect.forms.FormListItem;
import org.odk.collect.forms.MediaFile;
import org.odk.collect.forms.entries.EntryListItem;

import java.util.List;

public interface OpenRosaResponseParser {

    @Nullable List<EntryListItem> parseEntryList(Document document);
    @Nullable List<FormListItem> parseFormList(Document document);
    @Nullable List<MediaFile> parseManifest(Document document);
}
