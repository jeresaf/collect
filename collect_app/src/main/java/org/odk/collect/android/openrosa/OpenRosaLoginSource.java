package org.odk.collect.android.openrosa;

import static java.net.HttpURLConnection.HTTP_FORBIDDEN;
import static java.net.HttpURLConnection.HTTP_NOT_FOUND;
import static java.net.HttpURLConnection.HTTP_UNAUTHORIZED;

import org.jetbrains.annotations.NotNull;
import org.odk.collect.android.login.AdminUnitDetails;
import org.odk.collect.android.login.LoginDetails;
import org.odk.collect.android.login.LoginSource;
import org.odk.collect.android.login.LoginSourceException;
import org.odk.collect.android.utilities.DocumentFetchResult;
import org.odk.collect.android.utilities.WebCredentialsUtils;
import org.odk.collect.forms.FormSourceException;

import java.net.UnknownHostException;
import java.util.concurrent.Callable;

import javax.net.ssl.SSLException;

import timber.log.Timber;

public class OpenRosaLoginSource implements LoginSource {

    private final OpenRosaUserFetcher openRosaXMLFetcher;
    private final OpenRosaUserResponseParser openRosaResponseParser;
    private final WebCredentialsUtils webCredentialsUtils;

    private String serverURL;
    private String loginPath;
    private String adminUnitsPath;

    public OpenRosaLoginSource(String serverURL, WebCredentialsUtils webCredentialsUtils, OpenRosaUserHttpInterface openRosaUserHttpInterface, OpenRosaUserResponseParser openRosaUserResponseParser) {
        this.openRosaResponseParser = openRosaUserResponseParser;
        this.webCredentialsUtils = webCredentialsUtils;
        this.openRosaXMLFetcher = new OpenRosaUserFetcher(this.webCredentialsUtils, openRosaUserHttpInterface);
        this.serverURL = serverURL;
    }

    public LoginDetails fetchUser(String username, String password) throws LoginSourceException {
        DocumentFetchResult result = mapException(() -> openRosaXMLFetcher.getUserXML(username, password, getLoginURL()));
        Timber.e("Doc returns");
        if (result.errorMessage != null) {
            if (result.responseCode == HTTP_UNAUTHORIZED) {
                Timber.e("Unauthorised");
                throw new LoginSourceException.AuthRequired();
            } else if (result.responseCode == HTTP_NOT_FOUND) {
                Timber.e("Not found");
                throw new LoginSourceException.Unreachable(serverURL);
            } else {
                Timber.e("Random code: %d", result.responseCode);
                throw new LoginSourceException.ServerError(result.responseCode, serverURL);
            }
        } else {
            Timber.e("Empty error");
        }

        if (result.isOpenRosaResponse) {
            Timber.e("is Open rosa");
            LoginDetails loginDetails = openRosaResponseParser.parseLogin(result.doc);
            Timber.e("Login details: %s", loginDetails != null ? loginDetails.getUser_name() : "null");
            if (loginDetails != null) {
                return loginDetails;
            } else {
                throw new LoginSourceException.ParseError(serverURL);
            }
        } else {
            Timber.e("Not open rosa");
            throw new LoginSourceException.ServerNotOpenRosaError();
        }
    }

    public AdminUnitDetails fetchAdminUnits(String username) throws FormSourceException, LoginSourceException {
        DocumentFetchResult result = mapException(() -> openRosaXMLFetcher.getAdminUnitXML(username, getAdminUnitsURL()));
        Timber.e("Doc returns");
        if (result.errorMessage != null) {
            if (result.responseCode == HTTP_UNAUTHORIZED) {
                Timber.e("Unauthorised");
                throw new FormSourceException.AuthRequired();
            } else if (result.responseCode == HTTP_FORBIDDEN && isUserNotAllowedAccess(result.errorMessage)) {
                throw new LoginSourceException.UserNotAllowedAccess();
            } else if (result.responseCode == HTTP_NOT_FOUND) {
                Timber.e("Not found");
                throw new FormSourceException.Unreachable(serverURL);
            } else {
                Timber.e("Random code: %d", result.responseCode);
                throw new FormSourceException.ServerError(result.responseCode, serverURL);
            }
        } else {
            Timber.e("Empty error");
        }

        if (result.isOpenRosaResponse) {
            Timber.e("is Open rosa");
            AdminUnitDetails adminUnitDetails = openRosaResponseParser.parseAdminUnits(result.doc);
            if (adminUnitDetails != null) {
                return adminUnitDetails;
            } else {
                throw new FormSourceException.ParseError(serverURL);
            }
        } else {
            Timber.e("Not open rosa");
            throw new FormSourceException.ServerNotOpenRosaError();
        }
    }

    private boolean isUserNotAllowedAccess(String message) {
        return message != null && message.contains("User not allowed access to system");
    }

    public void updateUrl(String url) {
        this.serverURL = url;
    }

    public void updateWebCredentialsUtils(WebCredentialsUtils webCredentialsUtils) {
        this.openRosaXMLFetcher.updateWebCredentialsUtils(webCredentialsUtils);
    }

    @NotNull
    private <T> T mapException(Callable<T> callable) throws LoginSourceException {
        try {
            T result = callable.call();

            if (result != null) {
                return result;
            } else {
                throw new LoginSourceException.FetchError();
            }
        } catch (UnknownHostException e) {
            throw new LoginSourceException.Unreachable(serverURL);
        } catch (SSLException e) {
            throw new LoginSourceException.SecurityError(serverURL);
        } catch (Exception e) {
            throw new LoginSourceException.FetchError();
        }
    }

    @NotNull
    private String getLoginURL() {
        String loginUrl = serverURL;

        while (loginUrl.endsWith("/")) {
            loginUrl = loginUrl.substring(0, loginUrl.length() - 1);
        }

        loginUrl += loginPath;
        return loginUrl;
    }

    public void updateLoginPath(String path) {
        this.loginPath = path;
    }

    public String getLoginPath() {
        return loginPath;
    }

    @NotNull
    private String getAdminUnitsURL() {
        String adminUnitsUrl = serverURL;

        while (adminUnitsUrl.endsWith("/")) {
            adminUnitsUrl = adminUnitsUrl.substring(0, adminUnitsUrl.length() - 1);
        }

        adminUnitsUrl += adminUnitsPath;
        return adminUnitsUrl;
    }

    public void updateAdminUnitsPath(String path) {
        this.adminUnitsPath = path;
    }

    public String getAdminUnitsPath() {
        return adminUnitsPath;
    }

    public String getServerURL() {
        return serverURL;
    }

    public WebCredentialsUtils getWebCredentialsUtils() {
        return webCredentialsUtils;
    }
}
