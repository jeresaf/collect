package org.odk.collect.android.recordwork

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.net.URL
import java.security.KeyStore
import java.security.cert.CertificateFactory
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory

/**
 * U06-T04A: genuine Android-emulator TCP/TLS smoke to real Spring Boot and
 * PostgreSQL/Flyway on the same disposable runner. No mocked HTTP responses.
 *
 * The CA asset is generated at CI runtime and is intentionally NOT committed.
 * The test uses a per-connection trust store; it does not alter global trust,
 * production network security settings or hostname verification.
 *
 * This is a *health-only* connected transport probe, NOT authenticated
 * record-work or end-to-end qualification.
 */
@RunWith(AndroidJUnit4::class)
class ConnectedBackendTlsSmokeTest {
    private val url = "https://10.0.2.2:8443/actuator/health"

    @Test
    fun emulatorCallsRealBackendWithExplicitEphemeralCaAndDefaultHostnameVerification() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val certificate = instrumentation.context.assets.open("u06_t04a_ca.pem").use {
            CertificateFactory.getInstance("X.509").generateCertificate(it)
        }
        val keyStore = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null)
            setCertificateEntry("u06-t04a-only", certificate)
        }
        val trustManagers = TrustManagerFactory.getInstance(
            TrustManagerFactory.getDefaultAlgorithm()
        ).apply { init(keyStore) }.trustManagers
        val context = SSLContext.getInstance("TLS").apply {
            init(null, trustManagers, null)
        }

        val connection = URL(url).openConnection() as HttpsURLConnection
        connection.sslSocketFactory = context.socketFactory
        // Crucial: never set a permissive HostnameVerifier.
        connection.instanceFollowRedirects = false
        connection.connectTimeout = 15_000
        connection.readTimeout = 15_000
        connection.requestMethod = "GET"
        try {
            assertEquals(200, connection.responseCode)
            val mediaType = connection.contentType?.substringBefore(';')?.trim()?.lowercase()
            assertTrue(
                "Health endpoint must return a JSON media type",
                mediaType == "application/json" ||
                    (mediaType?.startsWith("application/") == true &&
                        mediaType.endsWith("+json"))
            )
            val response = connection.inputStream.bufferedReader().use { it.readText() }
            assertEquals("UP", JSONObject(response).getString("status"))
        } finally {
            connection.disconnect()
        }
    }

    @Test
    fun untrustedTestCaIsNotAcceptedByAndroidDefaultTrust() {
        val connection = URL(url).openConnection() as HttpsURLConnection
        connection.instanceFollowRedirects = false
        connection.connectTimeout = 15_000
        connection.readTimeout = 15_000
        var rejected = false
        try {
            // The short-lived CA is not a public/system root.
            connection.inputStream.use { it.read() }
        } catch (expected: javax.net.ssl.SSLException) {
            rejected = true
        } finally {
            connection.disconnect()
        }
        assertTrue("Untrusted certificate must fail closed", rejected)
    }
}
