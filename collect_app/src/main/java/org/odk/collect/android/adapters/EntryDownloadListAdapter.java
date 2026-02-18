/*
 * Copyright 2017 Nafundi
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

package org.odk.collect.android.adapters;

import static org.odk.collect.android.activities.EntryDownloadListActivity.ENTRYFORM_ID_KEY;
import static org.odk.collect.android.activities.EntryDownloadListActivity.ENTRYID_DISPLAY;
import static org.odk.collect.android.activities.EntryDownloadListActivity.ENTRYNAME;
import static org.odk.collect.android.activities.EntryDownloadListActivity.ENTRY_INSTANCE_ID;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.TextView;

import androidx.annotation.NonNull;

import org.odk.collect.android.R;
import org.odk.collect.android.entrymanagement.ServerEntryDetails;

import java.util.ArrayList;
import java.util.HashMap;

public class EntryDownloadListAdapter extends ArrayAdapter {

    private final ArrayList<HashMap<String, String>> filteredEntryList;
    private HashMap<String, ServerEntryDetails> instanceIdsToDetails;

    public EntryDownloadListAdapter(Context context, ArrayList<HashMap<String, String>> filteredEntryList,
                                    HashMap<String, ServerEntryDetails> instanceIdsToDetails) {
        super(context, R.layout.form_chooser_list_item_multiple_choice, filteredEntryList);
        this.filteredEntryList = filteredEntryList;
        this.instanceIdsToDetails = instanceIdsToDetails;
    }

    public void setFromIdsToDetails(HashMap<String, ServerEntryDetails> instanceIdsToDetails) {
        this.instanceIdsToDetails = instanceIdsToDetails;
    }

    private static class ViewHolder {
        TextView entryTitle;
        TextView entrySubtitle;
        TextView entryUpdateAlert;
    }

    public View getView(int position, View convertView, @NonNull ViewGroup parent) {
        View row = convertView;
        final ViewHolder holder;
        if (row == null) {
            holder = new ViewHolder();
            LayoutInflater inflater = (LayoutInflater) getContext().getSystemService(Context.LAYOUT_INFLATER_SERVICE);
            row = inflater.inflate(R.layout.form_chooser_list_item_multiple_choice, parent, false);

            holder.entryTitle = row.findViewById(R.id.form_title);
            holder.entrySubtitle = row.findViewById(R.id.form_subtitle);
            holder.entryUpdateAlert = row.findViewById(R.id.form_update_alert);
            row.setTag(holder);
        } else {
            holder = (ViewHolder) row.getTag();
        }

        final HashMap<String, String> entryAtPosition = filteredEntryList.get(position);
        final String instanceIdAtPosition = entryAtPosition.get(ENTRY_INSTANCE_ID);

        holder.entryTitle.setText(entryAtPosition.get(ENTRYNAME));
        holder.entrySubtitle.setText(entryAtPosition.get(ENTRYID_DISPLAY));

        if (instanceIdsToDetails.get(instanceIdAtPosition) != null
                && instanceIdsToDetails.get(instanceIdAtPosition).isUpdated()) {
            holder.entryUpdateAlert.setVisibility(View.VISIBLE);
        } else {
            holder.entryUpdateAlert.setVisibility(View.GONE);
        }
        
        return row;
    }
}
