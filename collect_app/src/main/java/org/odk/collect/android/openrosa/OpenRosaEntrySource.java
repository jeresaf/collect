package org.odk.collect.android.openrosa;

import static org.odk.collect.android.utilities.ApplicationConstants.BundleKeys.USERNAME;
import static org.odk.collect.settings.keys.ProjectKeys.KEY_METADATA_PHONENUMBER;
import static java.net.HttpURLConnection.HTTP_NOT_FOUND;
import static java.net.HttpURLConnection.HTTP_UNAUTHORIZED;

import org.jetbrains.annotations.NotNull;
import org.odk.collect.android.utilities.DocumentFetchResult;
import org.odk.collect.android.utilities.WebCredentialsUtils;
import org.odk.collect.forms.entries.EntryListItem;
import org.odk.collect.forms.entries.EntrySource;
import org.odk.collect.forms.entries.EntrySourceException;

import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.Callable;

import javax.net.ssl.SSLException;

import timber.log.Timber;

public class OpenRosaEntrySource implements EntrySource {

    private final OpenRosaXmlFetcher openRosaXMLFetcher;
    private final OpenRosaResponseParser openRosaResponseParser;
    private final WebCredentialsUtils webCredentialsUtils;

    private String serverURL;

    public OpenRosaEntrySource(String serverURL, OpenRosaHttpInterface openRosaHttpInterface, WebCredentialsUtils webCredentialsUtils, OpenRosaResponseParser openRosaResponseParser) {
        this.openRosaResponseParser = openRosaResponseParser;
        this.webCredentialsUtils = webCredentialsUtils;
        this.openRosaXMLFetcher = new OpenRosaXmlFetcher(openRosaHttpInterface, this.webCredentialsUtils);
        this.serverURL = serverURL;
    }


    @Override
    public List<EntryListItem> fetchEntryListURL(String username) throws EntrySourceException {
        DocumentFetchResult result = mapException(() -> openRosaXMLFetcher.getXML(getEntryListURL(username)));
        Timber.e("fetchEntryListURL Entry List: %s", result.doc.toString());
        if (result.errorMessage != null) {
            if (result.responseCode == HTTP_UNAUTHORIZED) {
                throw new EntrySourceException.AuthRequired();
            } else if (result.responseCode == HTTP_NOT_FOUND) {
                throw new EntrySourceException.Unreachable(serverURL);
            } else {
                throw new EntrySourceException.ServerError(result.responseCode, serverURL);
            }
        }

        if (result.isOpenRosaResponse) {
            List<EntryListItem> formList = openRosaResponseParser.parseEntryList(result.doc);

            if (formList != null) {
                return formList;
            } else {
                throw new EntrySourceException.ParseError(serverURL);
            }
        } else {
            throw new EntrySourceException.ServerNotOpenRosaError();
        }
    }

    @Override
    @NotNull
    public InputStream fetchEntry(String entryURL) throws EntrySourceException {
        Timber.e("fetchEntry Url for xml: %s", entryURL);
        HttpGetResult result = mapException(() -> openRosaXMLFetcher.fetch(getEntryURL(entryURL), null));

        if (result.getInputStream() == null) {
            throw new EntrySourceException.ServerError(result.getStatusCode(), serverURL);
        } else {
            return result.getInputStream();
        }
    }

    public void updateUrl(String url) {
        this.serverURL = url;
    }

    public void updateWebCredentialsUtils(WebCredentialsUtils webCredentialsUtils) {
        this.openRosaXMLFetcher.updateWebCredentialsUtils(webCredentialsUtils);
    }

    @NotNull
    private <T> T mapException(Callable<T> callable) throws EntrySourceException {
        try {
            T result = callable.call();

            if (result != null) {
                return result;
            } else {
                throw new EntrySourceException.FetchError();
            }
        } catch (UnknownHostException e) {
            throw new EntrySourceException.Unreachable(serverURL);
        } catch (SSLException e) {
            throw new EntrySourceException.SecurityError(serverURL);
        } catch (Exception e) {
            throw new EntrySourceException.FetchError();
        }
    }

    @NotNull
    private String getEntryURL(String path) throws EntrySourceException {
        String downloadEntryUrl = serverURL;

        while (downloadEntryUrl.endsWith("/")) {
            downloadEntryUrl = downloadEntryUrl.substring(0, downloadEntryUrl.length() - 1);
        }

        try {
            path = path.replaceAll(" ", "%20");
            path = path.replaceAll(":", "%3A");
            downloadEntryUrl += ("/" + path);
            URI uri = new URI(downloadEntryUrl);
            Timber.e("getEntryURL Uri string: %s", uri.toString());
            return uri.toString();
        } catch (Exception e) {
            Timber.e("getEntryURL Uri string exception: %s", e.getMessage());
            throw new EntrySourceException.ServerNotOpenRosaError();
        }

    }

    @NotNull
    private String getEntryListURL(String username) {
        String downloadListUrl = serverURL;

        while (downloadListUrl.endsWith("/")) {
            downloadListUrl = downloadListUrl.substring(0, downloadListUrl.length() - 1);
        }

        downloadListUrl += OpenRosaConstants.ENTRY_LIST;
        downloadListUrl += ("?username=" + username);

        return downloadListUrl;
    }

    public String getServerURL() {
        return serverURL;
    }

    public WebCredentialsUtils getWebCredentialsUtils() {
        return webCredentialsUtils;
    }
}
