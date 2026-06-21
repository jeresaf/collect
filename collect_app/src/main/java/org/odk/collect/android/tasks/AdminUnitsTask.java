package org.odk.collect.android.tasks;

import android.os.AsyncTask;

import androidx.core.util.Pair;

import org.odk.collect.android.listeners.AdminUnitsTaskListener;
import org.odk.collect.android.listeners.LoginTaskListener;
import org.odk.collect.android.login.AdminUnitDetails;
import org.odk.collect.android.login.LoginDetails;
import org.odk.collect.android.login.LoginDetailsFetcher;
import org.odk.collect.android.login.LoginSourceException;
import org.odk.collect.forms.FormSourceException;

import java.util.Map;

/**
 * Background task for logging in
 *
 * @author jerryaiko
 */
public class AdminUnitsTask extends AsyncTask<Map<String, String>, String, Pair<AdminUnitDetails, LoginSourceException>> {

    private final LoginDetailsFetcher loginDetailsFetcher;
    private AdminUnitsTaskListener stateListener;

    public AdminUnitsTask(LoginDetailsFetcher loginDetailsFetcher) {
        this.loginDetailsFetcher = loginDetailsFetcher;
    }

    @Override
    protected Pair<AdminUnitDetails, LoginSourceException> doInBackground(Map<String, String>... values) {

        AdminUnitDetails adminUnitDetails = null;
        LoginSourceException exception = null;

        try {
            adminUnitDetails = loginDetailsFetcher.fetchAdminUnits(values[0].get("username"));
        } catch (LoginSourceException e) {
            exception = e;
        } catch (FormSourceException e) {
            throw new RuntimeException(e);
        }

        return new Pair<>(adminUnitDetails, exception);
    }

    @Override
    protected void onPostExecute(Pair<AdminUnitDetails, LoginSourceException> result) {
        synchronized (this) {
            if (stateListener != null) {
                if (result.first != null) {
                    stateListener.adminUnitsComplete(result.first, result.second);
                } else {
                    stateListener.adminUnitsComplete(null, result.second);
                }
            }
        }
    }

    public void setDownloaderListener(AdminUnitsTaskListener sl) {
        synchronized (this) {
            stateListener = sl;
        }
    }
}
