package org.odk.collect.android.entrymanagement;

import android.content.Context;

import androidx.annotation.NonNull;

import org.odk.collect.android.R;
import org.odk.collect.material.MaterialProgressDialogFragment;

public class RefreshEntryListDialogFragment extends MaterialProgressDialogFragment {

    protected RefreshEntryListDialogFragmentListener listener;

    @Override
    public void onAttach(@NonNull Context context) {
        super.onAttach(context);

        if (context instanceof RefreshEntryListDialogFragmentListener) {
            listener = (RefreshEntryListDialogFragmentListener) context;
        }
        setTitle(getString(R.string.downloading_data));
        setMessage(getString(R.string.please_wait));
        setCancelable(false);
    }

    @Override
    protected String getCancelButtonText() {
        return getString(R.string.cancel_loading_form);
    }

    @Override
    protected OnCancelCallback getOnCancelCallback() {
        return () -> {
            listener.onCancelEntryLoading();
            dismiss();
            return true;
        };
    }

    public interface RefreshEntryListDialogFragmentListener {
            void onCancelEntryLoading();
    }
}
