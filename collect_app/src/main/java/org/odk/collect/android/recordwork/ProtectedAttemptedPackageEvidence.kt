package org.odk.collect.android.recordwork

import java.io.ByteArrayInputStream
import java.io.DataInputStream

/** Digest-only evidence of the actual attempted canonical envelope. */
internal data class ProtectedAttemptedPackageEvidence(
    val xmlBytes: Int, val xmlSha256: String,
    val attachments: List<ProtectedAttachmentEvidence>
) {
    fun requireMatch(remote: RemoteCompletionEvidence) {
        require(xmlBytes == remote.xmlBytes && xmlSha256 == remote.xmlSha256 &&
            attachments == remote.attachments) { "Completed remote content differs from attempted package" }
    }

    companion object {
        internal fun parse(canonical: ByteArray, b: QuarantinedWorkDraft,
                           attempt: ProtectedSubmittedReceipt): ProtectedAttemptedPackageEvidence {
            require(canonical.size in 1..(13 * 1024 * 1024 - 30) &&
                QuarantinedPrivateImageStore.digest(canonical) == attempt.packageSha256)
            DataInputStream(ByteArrayInputStream(canonical)).use { input ->
                for (value in listOf("DC_NATIVE_PACKAGE_V1", b.projectId,
                    b.actorContextSha256, b.workId, b.sourceSubmissionId,
                    b.targetFormVersionId, b.reservedInstanceId, attempt.generation)) {
                    require(input.readUTF() == value)
                }
                val n = input.readInt()
                require(n in 1..8_388_608)
                val xml = ByteArray(n)
                val xmlSha = try {
                    input.readFully(xml)
                    QuarantinedPrivateImageStore.digest(xml)
                } finally { xml.fill(0) }
                val count = input.readInt()
                require(count in 1..64)
                val seen = mutableSetOf<Pair<String, String>>()
                var previous = ""
                var total = n.toLong()
                val attachments = (0 until count).map {
                    val key = input.readUTF() to input.readUTF()
                    val filename = input.readUTF()
                    val mime = input.readUTF()
                    val length = input.readInt()
                    require(seen.add(key) && filename > previous && length in 1..1_500_000)
                    previous = filename
                    total += length
                    require(total <= 12L * 1024 * 1024)
                    val data = ByteArray(length)
                    try {
                        input.readFully(data)
                        val sha = QuarantinedPrivateImageStore.digest(data)
                        require(QuarantinedPrivateImageStore.sniffMime(data) == mime &&
                            filename == ProtectedNativePackageBuilder.filename(b, key, mime, sha))
                        ProtectedAttachmentEvidence(filename, mime, length, sha)
                    } finally { data.fill(0) }
                }
                require(input.read() == -1)
                return ProtectedAttemptedPackageEvidence(n, xmlSha, attachments)
            }
        }
    }
}
