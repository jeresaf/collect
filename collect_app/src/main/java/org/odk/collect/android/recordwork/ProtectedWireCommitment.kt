package org.odk.collect.android.recordwork

import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.UUID

/** Server wire commitment, deliberately distinct from the device-local package digest. */
internal object ProtectedWireCommitment {
    private val hash = Regex("[0-9a-f]{64}")
    private val name = Regex("[A-Za-z0-9._-]{1,255}")

    internal fun uuid(value: String): String = value.also {
        require(UUID.fromString(it).toString() == it)
    }

    internal fun compute(projectId: String, actorId: String, workId: String, sourceId: String,
                         targetVersionId: String, attemptId: String, instanceId: String,
                         workType: RecordWorkType, local: ProtectedAttemptedPackageEvidence): String {
        require(instanceId.startsWith("uuid:"))
        uuid(instanceId.removePrefix("uuid:"))
        require(local.xmlBytes in 1..8_388_608 && local.xmlSha256.matches(hash))
        require(local.attachments.size <= 64)
        var total = local.xmlBytes.toLong()
        val seen = mutableSetOf<String>()
        val digest = MessageDigest.getInstance("SHA-256")
        fun frame(value: String) {
            val bytes = value.toByteArray(Charsets.UTF_8)
            digest.update(ByteBuffer.allocate(4).putInt(bytes.size).array())
            digest.update(bytes)
        }
        listOf("DC_PROTECTED_WIRE_V1", uuid(projectId), uuid(actorId), uuid(workId),
            uuid(sourceId), uuid(targetVersionId), uuid(attemptId), instanceId, workType.name,
            local.xmlBytes.toString(), local.xmlSha256, local.attachments.size.toString()).forEach(::frame)
        for (part in local.attachments.sortedBy { it.filename }) {
            require(part.filename.matches(name) && !part.filename.contains("..") && seen.add(part.filename) &&
                part.mime in setOf("image/jpeg", "image/png") && part.bytes in 1..1_500_000 &&
                part.sha256.matches(hash))
            total += part.bytes
            require(total <= 12L * 1024 * 1024)
            listOf(part.filename, part.mime, part.bytes.toString(), part.sha256).forEach(::frame)
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}
