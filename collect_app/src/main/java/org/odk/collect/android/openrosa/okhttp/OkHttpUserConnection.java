package org.odk.collect.android.openrosa.okhttp;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.apache.commons.io.IOUtils;
import org.odk.collect.android.openrosa.CaseInsensitiveEmptyHeaders;
import org.odk.collect.android.openrosa.CaseInsensitiveHeaders;
import org.odk.collect.android.openrosa.HttpCredentialsInterface;
import org.odk.collect.android.openrosa.HttpGetResult;
import org.odk.collect.android.openrosa.HttpHeadResult;
import org.odk.collect.android.openrosa.HttpPostResult;
import org.odk.collect.android.openrosa.OpenRosaHttpInterface;
import org.odk.collect.android.openrosa.OpenRosaServerClient;
import org.odk.collect.android.openrosa.OpenRosaUserHttpInterface;
import org.odk.collect.shared.strings.Md5;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import okhttp3.Headers;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import timber.log.Timber;

public class OkHttpUserConnection extends OkHttpConnection implements OpenRosaUserHttpInterface {

    private static final String HTTP_CONTENT_TYPE_TEXT_XML = "text/xml";

    private final OkHttpOpenRosaServerClientProvider clientFactory;

    @NonNull
    private final String userAgent;

    public OkHttpUserConnection(@NonNull OkHttpOpenRosaServerClientProvider clientFactory, @NonNull FileToContentTypeMapper fileToContentTypeMapper, @NonNull String userAgent) {
        super(clientFactory, fileToContentTypeMapper, userAgent);
        this.clientFactory = clientFactory;
        this.userAgent = userAgent;
    }

    @NonNull
    public HttpGetResult login(@NonNull String username,
                         @NonNull String password,
                         @NonNull URI uri,
                         @Nullable HttpCredentialsInterface credentials,
                         @NonNull long contentLength) throws Exception {

        MultipartBody.Builder builder = new MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("username", username)
                .addFormDataPart("password", password);

        MultipartBody multipartBody = builder.build();

        Timber.e("Body, %s", builder.toString());

        OpenRosaServerClient httpClient = clientFactory.get(uri.getScheme(), userAgent, credentials);
        Request request = new Request.Builder()
                .url(uri.toURL())
                .post(multipartBody)
                .build();
        Timber.e("Request, %s", request.toString());
        Timber.e("Final post url, %s", uri.toURL());
        Response response = httpClient.makeRequest(request, new Date());

        if (response.code() == 204) {
            throw new Exception();
        }

        int statusCode = response.code();

        if (statusCode != HttpURLConnection.HTTP_OK) {
            discardEntityBytes(response);
            Timber.i("Error: %s (%s at %s", response.message(), String.valueOf(statusCode), uri.toString());

            return new HttpGetResult(null, new HashMap<String, String>(), "", statusCode);
        }

        ResponseBody body = response.body();

        if (body == null) {
            throw new Exception("No entity body returned from: " + uri.toString());
        }

        MediaType type = body.contentType();

        if (type != null && !type.toString().toLowerCase(Locale.ENGLISH).contains(HTTP_CONTENT_TYPE_TEXT_XML)) {
            discardEntityBytes(response);

            String error = "ContentType: " + type.toString() + " returned from: "
                    + uri.toString() + " is not " + HTTP_CONTENT_TYPE_TEXT_XML
                    + ".  This is often caused by a network proxy.  Do you need "
                    + "to login to your network?";

            throw new Exception(error);
        }

        InputStream downloadStream = body.byteStream();

        String hash = "";

        byte[] bytes = IOUtils.toByteArray(downloadStream);
        downloadStream = new ByteArrayInputStream(bytes);
        hash = Md5.getMd5Hash(new ByteArrayInputStream(bytes));

        Map<String, String> responseHeaders = new HashMap<>();
        Headers headers = response.headers();

        for (int i = 0; i < headers.size(); i++) {
            responseHeaders.put(headers.name(i), headers.value(i));
        }

        return new HttpGetResult(downloadStream, responseHeaders, hash, statusCode);

    }

    /*
    @NonNull
    public HttpGetResult fetchUnits(@NonNull String username,
                               @NonNull URI uri,
                               @Nullable HttpCredentialsInterface credentials,
                               @NonNull long contentLength) throws Exception {

        MultipartBody.Builder builder = new MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("username", username);

        MultipartBody multipartBody = builder.build();

        OpenRosaServerClient httpClient = clientFactory.get(uri.getScheme(), userAgent, credentials);
        Request request = new Request.Builder()
                .url(uri.toURL())
                .post(multipartBody)
                .build();
        Timber.e("Final post url, %s", uri.toURL());
        Response response = httpClient.makeRequest(request, new Date());

        if (response.code() == 204) {
            throw new Exception();
        }

        int statusCode = response.code();

        if (statusCode != HttpURLConnection.HTTP_OK) {
            discardEntityBytes(response);
            Timber.i("Error: %s (%s at %s", response.message(), String.valueOf(statusCode), uri.toString());

            return new HttpGetResult(null, new HashMap<String, String>(), "", statusCode);
        }

        ResponseBody body = response.body();

        if (body == null) {
            throw new Exception("No entity body returned from: " + uri.toString());
        }

        MediaType type = body.contentType();

        if (type != null && !type.toString().toLowerCase(Locale.ENGLISH).contains(HTTP_CONTENT_TYPE_TEXT_XML)) {
            discardEntityBytes(response);

            String error = "ContentType: " + type.toString() + " returned from: "
                    + uri.toString() + " is not " + HTTP_CONTENT_TYPE_TEXT_XML
                    + ".  This is often caused by a network proxy.  Do you need "
                    + "to login to your network?";

            throw new Exception(error);
        }

        InputStream downloadStream = body.byteStream();

        String hash = "";

        byte[] bytes = IOUtils.toByteArray(downloadStream);
        downloadStream = new ByteArrayInputStream(bytes);
        hash = Md5.getMd5Hash(new ByteArrayInputStream(bytes));

        Map<String, String> responseHeaders = new HashMap<>();
        Headers headers = response.headers();

        for (int i = 0; i < headers.size(); i++) {
            responseHeaders.put(headers.name(i), headers.value(i));
        }

        return new HttpGetResult(downloadStream, responseHeaders, hash, statusCode);

    }

     */

    /**
     * Utility to ensure that the entity stream of a response is drained of
     * bytes.
     * Apparently some servers require that we manually read all data from the
     * stream to allow its re-use.  Please add more details or bug ID here if
     * you know them.
     */
    private void discardEntityBytes(Response response) {
        ResponseBody body = response.body();
        if (body != null) {
            try (InputStream is = body.byteStream()) {
                while (is.read() != -1) {
                    // loop until all bytes read
                }
            } catch (Exception e) {
                Timber.i(e);
            }
        }
    }
}
