package org.odk.collect.android.fragments.dialogs;

import android.app.Dialog;
import android.content.Context;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;

import org.odk.collect.android.R;
import org.odk.collect.material.MaterialProgressDialogFragment;

public class LoginDialogFragment extends MaterialProgressDialogFragment {

    protected LoginDialogFragmentListener listener;

    @Override
    public void onAttach(@NonNull Context context) {
        super.onAttach(context);

        if (context instanceof LoginDialogFragmentListener) {
            listener = (LoginDialogFragmentListener) context;
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
            listener.onCancelLogin();
            dismiss();
            return true;
        };
    }

    public interface LoginDialogFragmentListener {
            void onCancelLogin();
    }
}
