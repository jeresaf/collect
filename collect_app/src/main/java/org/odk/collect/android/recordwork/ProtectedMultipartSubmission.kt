package org.odk.collect.android.recordwork

import android.util.Base64
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import org.odk.collect.settings.SettingsProvider
import org.odk.collect.settings.keys.ProjectKeys
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.URL
import java.nio.file.Files
import java.util.UUID
import javax.net.ssl.HttpsURLConnection

/** A receipt is not yet a reconciled server record or durable local status. */
internal data class ProtectedTransportResult(
    val httpStatus: Int?,
    val acknowledged: Boolean,
    val instanceId: String?,
    val packageSha256: String
)

/** Dedicated sender, not the standard ODK uploader. No background or retry path. */
internal class ProtectedMultipartSubmission(
    private val scratch: File,
    private val root: File,
    private val settings: SettingsProvider,
    private val openConnection: (URL) -> HttpsURLConnection = { it.openConnection() as HttpsURLConnection }
) {
    fun send(
        projectId: String, binding: QuarantinedWorkDraft, generation: String,
        expectedPackageSha256: String
    ): ProtectedTransportResult {
        val admission = ProtectedSubmissionAdmission(scratch, root, settings)
            .inspect(projectId, binding, generation, expectedPackageSha256)
        var result: ProtectedTransportResult? = null
        ProtectedCommittedDraftStore(scratch, root, settings)
            .withFinalizedSubmissionContent(projectId, binding, generation) { persisted, canonical ->
                require(persisted.ciphertextSha256 == admission.ciphertextSha256 &&
                    persisted.evidence.packaged.packageSha256 == admission.packageSha256)
                val values = settings.getUnprotectedSettings(projectId)
                val paths = RecordWorkApiPaths.fromProjectUrl(values.getString(ProjectKeys.KEY_SERVER_URL).orEmpty())
                val user = values.getString(ProjectKeys.KEY_USERNAME).orEmpty()
                val password = values.getString(ProjectKeys.KEY_PASSWORD).orEmpty()
                require(user.isNotBlank() && password.isNotBlank() &&
                    user.none { it == ':' || it == '\n' || it == '\r' } &&
                    password.none { it == '\n' || it == '\r' } &&
                    paths.submission() == admission.endpoint &&
                    QuarantinedWorkDraftVault.sha256(projectId + "\n" + paths.records() + "\n" + user) ==
                        admission.actorContextSha256)
                val parts = decode(canonical, binding, generation, persisted.evidence.packaged)
                try {
                    val boundary = "dc" + UUID.randomUUID().toString().replace("-", "")
                    val headers = parts.map { part ->
                        ("--$boundary\r\nContent-Disposition: form-data; name=\"" +
                            part.name + "\"; filename=\"" + part.filename + "\"\r\nContent-Type: " +
                            part.mime + "\r\n\r\n").toByteArray(Charsets.UTF_8)
                    }
                    val suffix = "--$boundary--\r\n".toByteArray(Charsets.UTF_8)
                    val crlf = "\r\n".toByteArray(Charsets.UTF_8)
                    val size = headers.sumOf { it.size.toLong() } +
                        parts.sumOf { it.bytes.size.toLong() + crlf.size } + suffix.size
                    require(size in 1..(13L * 1024 * 1024))
                    // Refuse boundary collisions even in user-controlled binary bytes.
                    val needle = boundary.toByteArray(Charsets.UTF_8)
                    require(parts.none { contains(it.bytes, needle) })
                    // Work-level durable conservative latch: even a crash before
                    // sending requires explicit later reconciliation. Never auto-clear.
                    reserveAttempt(binding, admission)
                    result = transmit(admission, user, password, boundary, headers, parts, suffix, crlf, size)
                } finally { parts.forEach { it.bytes.fill(0) } }
            }
        return requireNotNull(result)
    }

    private data class Part(val name: String, val filename: String, val mime: String, val bytes: ByteArray)

    private fun decode(
        canonical: ByteArray, binding: QuarantinedWorkDraft, generation: String,
        evidence: ProtectedNativePackageEvidence
    ): List<Part> {
        require(canonical.size in 1..13 * 1024 * 1024 &&
            QuarantinedPrivateImageStore.digest(canonical) == evidence.packageSha256)
        val parts = mutableListOf<Part>()
        try {
            DataInputStream(ByteArrayInputStream(canonical)).use { input ->
                for (value in listOf("DC_NATIVE_PACKAGE_V1", binding.projectId,
                    binding.actorContextSha256, binding.workId, binding.sourceSubmissionId,
                    binding.targetFormVersionId, binding.reservedInstanceId, generation)) {
                    require(input.readUTF() == value)
                }
                val n = input.readInt()
                require(n == evidence.xml.xmlBytes && n in 1..8_388_608)
                val xml = ByteArray(n)
                parts += Part("xml_submission_file", "submission.xml", "text/xml", xml)
                input.readFully(xml)
                require(QuarantinedPrivateImageStore.digest(xml) == evidence.xml.xmlSha256)
                val count = input.readInt()
                require(count in 1..64 && count == evidence.attachments.size)
                val seen = mutableSetOf<Pair<String, String>>()
                var previous = ""
                repeat(count) {
                    val key = input.readUTF() to input.readUTF()
                    val name = input.readUTF()
                    val mime = input.readUTF()
                    val length = input.readInt()
                    val item = requireNotNull(evidence.attachments[key])
                    require(seen.add(key) && name > previous && length in 1..1_500_000 &&
                        length == item.bytes && mime == item.mime && name == item.filename &&
                        name == ProtectedNativePackageBuilder.filename(binding, key, mime, item.sha256) &&
                        evidence.xml.mediaReferenceDigests[key] == QuarantinedWorkDraftVault.sha256(name))
                    previous = name
                    val bytes = ByteArray(length)
                    parts += Part(name, name, mime, bytes)
                    input.readFully(bytes)
                    require(QuarantinedPrivateImageStore.digest(bytes) == item.sha256 &&
                        QuarantinedPrivateImageStore.sniffMime(bytes) == mime)
                }
                require(input.read() == -1 && seen == evidence.attachments.keys &&
                    parts.sumOf { it.bytes.size.toLong() } == evidence.totalContentBytes)
            }
            return parts
        } catch (failure: Throwable) {
            parts.forEach { it.bytes.fill(0) }
            throw failure
        }
    }

    private fun reserveAttempt(binding: QuarantinedWorkDraft, admission: ProtectedSubmissionAdmissionEvidence) {
        val area = File(File(File(root, "recordwork-committed"), binding.projectId), binding.workId)
        require(area.isDirectory && area.canonicalFile == area.absoluteFile)
        val marker = File(area, "submission-attempt.aesgcm")
        require(!Files.isSymbolicLink(marker.toPath()))
        val aad = ("DC_SUBMISSION_ATTEMPT_V1\n" + binding.projectId + "\n" +
            binding.actorContextSha256 + "\n" + binding.workId).toByteArray(Charsets.UTF_8)
        val clear = listOf(admission.generation, admission.packageSha256, admission.ciphertextSha256,
            binding.reservedInstanceId, binding.targetFormVersionId).joinToString("\n").toByteArray(Charsets.UTF_8)
        try {
            val sealed = AndroidKeyStorePrivateDraftCipher().seal(clear, aad)
            // Exclusive creation; a zero-length or partially written latch is
            // deliberately also blocking. Never interpret it as safe to resend.
            require(marker.createNewFile()) { "A prior submission attempt requires reconciliation" }
            FileOutputStream(marker).use { it.write(sealed); it.fd.sync() }
        } finally { clear.fill(0) }
    }

    private fun transmit(
        admission: ProtectedSubmissionAdmissionEvidence, user: String, password: String,
        boundary: String, headers: List<ByteArray>, parts: List<Part>, suffix: ByteArray,
        crlf: ByteArray, size: Long
    ): ProtectedTransportResult {
        var connection: HttpsURLConnection? = null
        var status: Int? = null
        try {
            val conn = openConnection(URL(admission.endpoint))
            connection = conn
            conn.instanceFollowRedirects = false
            conn.useCaches = false
            conn.connectTimeout = 20_000
            conn.readTimeout = 30_000
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Authorization", "Basic " + Base64.encodeToString(
                "$user:$password".toByteArray(Charsets.UTF_8), Base64.NO_WRAP))
            conn.setRequestProperty(admission.workHeaderName, admission.workHeaderValue)
            conn.setRequestProperty("X-OpenRosa-Version", "1.0")
            conn.setRequestProperty("Cache-Control", "no-store")
            conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            conn.setFixedLengthStreamingMode(size)
            conn.outputStream.use { out ->
                parts.forEachIndexed { index, part ->
                    out.write(headers[index]); out.write(part.bytes); out.write(crlf)
                }
                out.write(suffix)
            }
            status = conn.responseCode
            if (status != 201) return ProtectedTransportResult(status, false, null, admission.packageSha256)
            require(conn.contentType?.substringBefore(';')?.trim()?.lowercase() in
                setOf("text/xml", "application/xml"))
            val receipt = conn.inputStream.use { input ->
                val buffer = ByteArray(8192)
                val out = java.io.ByteArrayOutputStream()
                try {
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        require(out.size() + n <= 65_536)
                        out.write(buffer, 0, n)
                    }
                    out.toByteArray()
                } finally { buffer.fill(0) }
            }
            try {
                requireReceipt(receipt, admission.reservedInstanceId)
            } finally { receipt.fill(0) }
            return ProtectedTransportResult(status, true, admission.reservedInstanceId, admission.packageSha256)
        } catch (_: Exception) {
            // A failed acknowledgement is ambiguous. Do not release the latch,
            // retry, send to a redirected URL, or claim that the server failed.
            return ProtectedTransportResult(status, false, null, admission.packageSha256)
        } finally { connection?.disconnect() }
    }

    private fun requireReceipt(bytes: ByteArray, expected: String) {
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
        parser.setInput(ByteArrayInputStream(bytes), "UTF-8")
        val ns = "http://openrosa.org/http/response"
        require(parser.nextTag() == XmlPullParser.START_TAG && parser.name == "OpenRosaResponse" &&
            parser.namespace == ns)
        val seen = mutableSetOf<String>()
        while (parser.nextTag() == XmlPullParser.START_TAG) {
            val name = parser.name
            require(parser.namespace == ns && seen.add(name) && parser.attributeCount == 0)
            val value = parser.nextText()
            when (name) {
                "message" -> require(value.isNotBlank())
                "status" -> require(value == "201")
                "instanceID" -> require(value == expected)
                else -> throw IOException("Unexpected receipt field")
            }
        }
        require(parser.name == "OpenRosaResponse" && seen == setOf("message", "status", "instanceID"))
        require(parser.next() == XmlPullParser.END_DOCUMENT)
    }

    private fun contains(bytes: ByteArray, needle: ByteArray): Boolean {
        for (i in 0..bytes.size - needle.size) {
            if (needle.indices.all { bytes[i + it] == needle[it] }) return true
        }
        return false
    }
}
