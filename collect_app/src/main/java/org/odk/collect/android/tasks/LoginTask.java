package org.odk.collect.android.tasks;

import android.os.AsyncTask;

import androidx.core.util.Pair;

import org.odk.collect.android.listeners.LoginTaskListener;
import org.odk.collect.android.login.LoginDetails;
import org.odk.collect.android.login.LoginDetailsFetcher;
import org.odk.collect.android.login.LoginSourceException;

import java.util.Map;

/**
 * Background task for logging in
 *
 * @author jerryaiko
 */
public class LoginTask extends AsyncTask<Map<String, String>, String, Pair<LoginDetails, LoginSourceException>> {

    private final LoginDetailsFetcher loginDetailsFetcher;
    private LoginTaskListener stateListener;

    public LoginTask(LoginDetailsFetcher loginDetailsFetcher) {
        this.loginDetailsFetcher = loginDetailsFetcher;
    }

    @Override
    protected Pair<LoginDetails, LoginSourceException> doInBackground(Map<String, String>... values) {

        LoginDetails loginDetails = null;
        LoginSourceException exception = null;

        try {
            loginDetails = loginDetailsFetcher.fetchUserDetails(values[0].get("username"), values[0].get("password"));
        } catch (LoginSourceException e) {
            exception = e;
        }

        return new Pair<>(loginDetails, exception);
    }

    @Override
    protected void onPostExecute(Pair<LoginDetails, LoginSourceException> result) {
        synchronized (this) {
            if (stateListener != null) {
                if (result.first != null) {
                    stateListener.loginComplete(result.first, result.second);
                } else {
                    stateListener.loginComplete(null, result.second);
                }
            }
        }
    }

    public void setDownloaderListener(LoginTaskListener sl) {
        synchronized (this) {
            stateListener = sl;
        }
    }
}
