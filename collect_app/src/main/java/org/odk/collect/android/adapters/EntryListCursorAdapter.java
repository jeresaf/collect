/*
 * Copyright 2017 SDRC
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

import android.content.Context;
import android.database.Cursor;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.SimpleCursorAdapter;
import android.widget.TextView;

import org.odk.collect.android.R;
import org.odk.collect.android.database.entries.DatabaseEntryColumns;
import org.odk.collect.android.external.EntryProvider;
import org.odk.collect.android.utilities.FormsRepositoryProvider;
import org.odk.collect.forms.Form;
import org.odk.collect.forms.entries.Entry;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.Locale;

import timber.log.Timber;

public class EntryListCursorAdapter extends SimpleCursorAdapter {
    private final Context context;
    private final boolean shouldCheckDisabled;

    public EntryListCursorAdapter(Context context, int layout, Cursor c, String[] from, int[] to, boolean shouldCheckDisabled) {
        super(context, layout, c, from, to);
        this.context = context;
        this.shouldCheckDisabled = shouldCheckDisabled;
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        View view = super.getView(position, convertView, parent);

        ImageView imageView = view.findViewById(R.id.image);
        setImageFromStatus(imageView);

        setUpSubtext(view);

        // Some form lists never contain disabled items; if so, we're done.
        // Update: This only seems to be the case in Edit Saved Forms and it's not clear why...
        if (!shouldCheckDisabled) {
            return view;
        }

        boolean formExists = false;
        boolean isFormEncrypted = false;

        String formId = getCursor().getString(getCursor().getColumnIndex(DatabaseEntryColumns.JR_FORM_ID));
        String formVersion = getCursor().getString(getCursor().getColumnIndex(DatabaseEntryColumns.JR_VERSION));
        Form form = new FormsRepositoryProvider(context.getApplicationContext()).get().getLatestByFormIdAndVersion(formId, formVersion);

        Timber.i("formId is %s", formId);
        Timber.i("formVersion is %s", formVersion);

        //Check using formID only
        if (form == null) {
            Timber.i("Form is initially null, Check using formID only");
            form = new FormsRepositoryProvider(context.getApplicationContext()).get().getLatestByFormId(formId);
            //deprecated fix
            //check with spaces replaced with underscores
            if (form == null) {
                Timber.i("Form is still null, check with spaces replaced with underscores");
                form = new FormsRepositoryProvider(context.getApplicationContext()).get().getLatestByFormId(formId.replace(" ", "_"));
                //check with underscores replaced with spaces
                if (form == null) {
                    Timber.i("Form is still null, check with underscores replaced with spaces");
                    form = new FormsRepositoryProvider(context.getApplicationContext()).get().getLatestByFormId(formId.replace("_", " "));
                }
            }
            if (form != null) {
                Timber.i("FormId is %s", form.getFormId());
                Timber.i("formVersion is %s", form.getVersion());
            }
        }

        Timber.i("Form is null again? %b", form == null);

        if (form != null) {
            String base64RSAPublicKey = form.getBASE64RSAPublicKey();
            formExists = true;
            isFormEncrypted = base64RSAPublicKey != null;
        }

        long date = getCursor().getLong(getCursor().getColumnIndex(DatabaseEntryColumns.DELETED_DATE));

        if (date != 0 || !formExists || isFormEncrypted) {
            String disabledMessage;

            if (date != 0) {
                try {
                    String deletedTime = context.getString(R.string.deleted_on_date_at_time);
                    disabledMessage = new SimpleDateFormat(deletedTime, Locale.getDefault()).format(new Date(date));
                } catch (IllegalArgumentException e) {
                    Timber.e(e);
                    disabledMessage = context.getString(R.string.submission_deleted);
                }
            } else if (!formExists) {
                disabledMessage = context.getString(R.string.deleted_form);
            } else {
                disabledMessage = context.getString(R.string.encrypted_form);
            }

            setDisabled(view, disabledMessage);
        } else {
            setEnabled(view);
        }

        return view;
    }

    private void setEnabled(View view) {
        final TextView formTitle = view.findViewById(R.id.form_title);
        final TextView formSubtitle = view.findViewById(R.id.form_subtitle);
        final TextView disabledCause = view.findViewById(R.id.form_subtitle2);
        final ImageView imageView = view.findViewById(R.id.image);

        view.setEnabled(true);
        disabledCause.setVisibility(View.GONE);

        formTitle.setAlpha(1f);
        formSubtitle.setAlpha(1f);
        disabledCause.setAlpha(1f);
        imageView.setAlpha(1f);
    }

    private void setDisabled(View view, String disabledMessage) {
        final TextView formTitle = view.findViewById(R.id.form_title);
        final TextView formSubtitle = view.findViewById(R.id.form_subtitle);
        final TextView disabledCause = view.findViewById(R.id.form_subtitle2);
        final ImageView imageView = view.findViewById(R.id.image);

        view.setEnabled(false);
        disabledCause.setVisibility(View.VISIBLE);
        disabledCause.setText(disabledMessage);

        // Material design "disabled" opacity is 38%.
        formTitle.setAlpha(0.38f);
        formSubtitle.setAlpha(0.38f);
        disabledCause.setAlpha(0.38f);
        imageView.setAlpha(0.38f);
    }

    private void setUpSubtext(View view) {
        Timber.e("Column names: %s", Arrays.toString(getCursor().getColumnNames()));
        String date = getCursor().getString(getCursor().getColumnIndex(DatabaseEntryColumns.DATE));
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault());
        Timber.e("Date 1: %s", date);
        if(date == null || date.isEmpty()) {
            sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS", Locale.getDefault());
            date = getCursor().getString(getCursor().getColumnIndex(DatabaseEntryColumns.END));
            Timber.e("Date 2: %s", date);
        }
        String status = getCursor().getString(getCursor().getColumnIndex(DatabaseEntryColumns.STATUS));
        String subtext = "";
        if(date != null && !date.isEmpty()) {
            try {
                subtext = EntryProvider.getDisplaySubtext(context, status, sdf.parse(date));
                Timber.e("Subtext : %s", subtext);
            } catch (ParseException e) {
                Timber.e("Failed to parse date: %s", date);
            }
        } else {
            Timber.e("Date is empty");
        }

        final TextView formSubtitle = view.findViewById(R.id.form_subtitle);
        formSubtitle.setText(subtext);
    }

    private void setImageFromStatus(ImageView imageView) {
        String formStatus = getCursor().getString(getCursor().getColumnIndex(DatabaseEntryColumns.STATUS));

        int imageResourceId = getFormStateImageResourceIdForStatus(formStatus);
        imageView.setImageResource(imageResourceId);
        imageView.setTag(imageResourceId);
    }

    public static int getFormStateImageResourceIdForStatus(String formStatus) {
        switch (formStatus) {
            case Entry.STATUS_INCOMPLETE:
                return R.drawable.form_state_saved_circle;
            case Entry.STATUS_COMPLETE:
                return R.drawable.form_state_finalized_circle;
            case Entry.STATUS_SUBMITTED:
                return R.drawable.form_state_submitted_circle;
            case Entry.STATUS_SUBMISSION_FAILED:
                return R.drawable.form_state_submission_failed_circle;
        }

        return -1;
    }
}
