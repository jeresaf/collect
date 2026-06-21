package org.odk.collect.android.openrosa;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.kxml2.io.KXmlParser;
import org.kxml2.kdom.Document;
import org.odk.collect.android.openrosa.okhttp.OkHttpUserConnection;
import org.odk.collect.android.utilities.DocumentFetchResult;
import org.odk.collect.android.utilities.WebCredentialsUtils;
import org.xmlpull.v1.XmlPullParser;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;

import timber.log.Timber;

/**
 * This is only used inside {@link OpenRosaFormSource} and could potentially be absorbed there. Some
 * of the parsing logic here might be better broken out somewhere else however if it can be used
 * in other scenarios.
 */
class OpenRosaUserFetcher extends OpenRosaXmlFetcher{

    private static final String HTTP_CONTENT_TYPE_TEXT_XML = "text/xml";

    private final OpenRosaUserHttpInterface httpInterface;
    private final WebCredentialsUtils webCredentialsUtils;

    OpenRosaUserFetcher(WebCredentialsUtils webCredentialsUtils, OpenRosaUserHttpInterface httpUserInterface) {
        super((OkHttpUserConnection) httpUserInterface, webCredentialsUtils);
        this.httpInterface = httpUserInterface;
        this.webCredentialsUtils = webCredentialsUtils;
    }

    /**
     * Gets an XML document for a given url
     *
     * @param urlString - url of the XML document
     * @return DocumentFetchResult - an object that contains the results of the "get" operation
     */

    @SuppressWarnings("PMD.AvoidRethrowingException")
    public DocumentFetchResult getUserXML(@NonNull String username, @NonNull String password, String urlString) throws Exception {

        // parse response
        Document doc;
        HttpGetResult inputStreamResult;

        try {
            inputStreamResult = fetchUser(username, password, urlString);

            if (inputStreamResult.getStatusCode() != HttpURLConnection.HTTP_OK) {
                String error = "getXML failed while accessing "
                        + urlString + " with status code: " + inputStreamResult.getStatusCode();
                Timber.e(error);
                return new DocumentFetchResult(error, inputStreamResult.getStatusCode());
            }

            try (InputStream resultInputStream = inputStreamResult.getInputStream();
                 InputStreamReader oStreamReader = new InputStreamReader(resultInputStream, "UTF-8")) {

                StringBuilder res = new StringBuilder();
                int data = oStreamReader.read();
                while(data != -1){
                    char theChar = (char) data;
                    res.append(theChar);
                    data = oStreamReader.read();
                }

                String fn = res.toString().trim();
                Timber.e(fn);

                InputStream is = new ByteArrayInputStream( fn.getBytes() );
                InputStreamReader streamReader = new InputStreamReader(is, "UTF-8");

                doc = new Document();
                KXmlParser parser = new KXmlParser();
                parser.setInput(streamReader);
                parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true);

                doc.parse(parser);
            }
        } catch (Exception e) {
            throw e;
        }

        return new DocumentFetchResult(doc, inputStreamResult.isOpenRosaResponse(), inputStreamResult.getHash());
    }

    /**
     * Gets an XML document for a given url
     *
     * @param urlString - url of the XML document
     * @return DocumentFetchResult - an object that contains the results of the "get" operation
     */

    @SuppressWarnings("PMD.AvoidRethrowingException")
    public DocumentFetchResult getAdminUnitXML(@NonNull String username, String urlString) throws Exception {

        // parse response
        Document doc;
        HttpGetResult inputStreamResult;

        try {
            inputStreamResult = fetchAdminUnits(username, urlString);

            String fn;
            try (InputStream resultInputStream = inputStreamResult.getInputStream();
                 InputStreamReader oStreamReader = new InputStreamReader(resultInputStream, "UTF-8")) {

                StringBuilder res = new StringBuilder();
                int data = oStreamReader.read();
                while(data != -1){
                    char theChar = (char) data;
                    res.append(theChar);
                    data = oStreamReader.read();
                }

                fn = res.toString().trim();
                Timber.e(fn);
            }

            if (inputStreamResult.getStatusCode() != HttpURLConnection.HTTP_OK) {
                String error = "getXML failed while accessing "
                        + urlString + " with status code: " + inputStreamResult.getStatusCode() + ": " + fn;
                Timber.e(error);
                return new DocumentFetchResult(error, inputStreamResult.getStatusCode());
            }

            try {
                InputStream is = new ByteArrayInputStream( fn.getBytes() );
                InputStreamReader streamReader = new InputStreamReader(is, "UTF-8");

                doc = new Document();
                KXmlParser parser = new KXmlParser();
                parser.setInput(streamReader);
                parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true);

                doc.parse(parser);
            }
        } catch (Exception e) {
            throw e;
        }

        return new DocumentFetchResult(doc, inputStreamResult.isOpenRosaResponse(), inputStreamResult.getHash());
    }

    /**
     * Creates a Http connection and input stream
     *
     * @param username username of the user
     * @param password password of the user
     * @param downloadUrl uri of the stream
     * @return HttpPostResult - An object containing the Stream, Hash and Headers
     * @throws Exception - Can throw a multitude of Exceptions, such as MalformedURLException or IOException
     */

    @NonNull
    public HttpGetResult fetchUser(@NonNull String username, @NonNull String password, @NonNull String downloadUrl) throws Exception {
        URI uri;
        try {
            // assume the downloadUrl is escaped properly
            URL url = new URL(downloadUrl);
            uri = url.toURI();
        } catch (MalformedURLException | URISyntaxException e) {
            Timber.e(e, "Unable to get a URI for download URL : %s  due to %s : ", downloadUrl, e.getMessage());
            throw e;
        }

        if (uri.getHost() == null) {
            Timber.e("Invalid server URL (no hostname): %s", downloadUrl);
            throw new Exception("Invalid server URL (no hostname): " + downloadUrl);
        }

        long contentLength = 10000000L;

        return httpInterface.login(username, password, uri, webCredentialsUtils.getCredentials(uri), contentLength);
    }

    /**
     * Creates a Http connection and input stream
     *
     * @param username username of the user
     * @param downloadUrl uri of the stream
     * @return HttpPostResult - An object containing the Stream, Hash and Headers
     * @throws Exception - Can throw a multitude of Exceptions, such as MalformedURLException or IOException
     */

    @NonNull
    public HttpGetResult fetchAdminUnits(@NonNull String username, @NonNull String downloadUrl) throws Exception {
        URI uri;
        try {
            // assume the downloadUrl is escaped properly
            URL url = new URL(downloadUrl);
            uri = url.toURI();
        } catch (MalformedURLException | URISyntaxException e) {
            Timber.e(e, "Unable to get a URI for download URL : %s  due to %s : ", downloadUrl, e.getMessage());
            throw e;
        }

        if (uri.getHost() == null) {
            Timber.e("Invalid server URL (no hostname): %s", downloadUrl);
            throw new Exception("Invalid server URL (no hostname): " + downloadUrl);
        }

        long contentLength = 10000000L;

        return httpInterface.fetchUnits(username, uri, webCredentialsUtils.getCredentials(uri), contentLength);
    }

}
