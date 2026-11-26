package org.odk.collect.android.recordwork

import org.json.JSONObject
import org.json.JSONTokener
import java.util.UUID

/** Digest-only remote evidence. No receipt, write, retry or editor capability. */
internal data class RemoteCompletionEvidence(
    val workId: String, val sourceSubmissionId: String, val sourceInstanceId: String,
    val targetFormVersionId: String, val formId: String, val visitKey: String,
    val submissionId: String, val instanceId: String,
    val xmlBytes: Int, val xmlSha256: String, val attachments: List<ProtectedAttachmentEvidence>,
    val canEdit: Boolean = false, val canUpload: Boolean = false
) {
    init { require(!canEdit && !canUpload) }

    companion object {
        private val hash = Regex("[0-9a-f]{64}")
        private val name = Regex("protected-[0-9a-f]{64}\\.(jpg|png|webp)")
        private fun JSONObject.exactKeys(vararg names: String) {
            require(keys().asSequence().toSet() == names.toSet())
        }
        private fun JSONObject.text(key: String) = get(key) as String
        private fun JSONObject.count(key: String, max: Int): Int {
            val value = get(key)
            require(value is Int || value is Long)
            val count = (value as Number).toLong()
            require(count in 1L..max.toLong())
            return count.toInt()
        }
        private fun uuid(value: String) = value.also { require(UUID.fromString(it).toString() == it) }

        internal fun parse(json: String, b: QuarantinedWorkDraft, child: RemoteRecord): RemoteCompletionEvidence {
            require(json.length <= 1_048_576)
            val tokener = JSONTokener(json)
            val obj = tokener.nextValue() as JSONObject
            require(tokener.nextClean() == '\u0000')
            obj.exactKeys("workId", "sourceSubmissionId", "sourceInstanceId", "targetFormVersionId",
                "formId", "visitKey", "submissionId", "instanceId", "xmlBytes", "xmlSha256", "attachments")
            val work = uuid(obj.text("workId"))
            val source = uuid(obj.text("sourceSubmissionId"))
            val sourceInstance = obj.text("sourceInstanceId")
            val target = uuid(obj.text("targetFormVersionId"))
            val form = uuid(obj.text("formId"))
            val visit = obj.text("visitKey")
            val submitted = uuid(obj.text("submissionId"))
            val instance = obj.text("instanceId")
            require(b.workType == RecordWorkType.FOLLOW_UP && b.validationTransfers == 0 &&
                !b.requiresDeprecatedId && work == b.workId && source == b.sourceSubmissionId &&
                sourceInstance == b.sourceInstanceId && target == b.targetFormVersionId &&
                visit == b.visitKey && visit.isNotBlank() &&
                submitted == child.submissionId && submitted != source &&
                instance == b.reservedInstanceId && instance != sourceInstance && instance == child.instanceId &&
                child.formVersionId == target && child.formId == form)
            val xmlBytes = obj.count("xmlBytes", 8 * 1024 * 1024)
            val xmlHash = obj.text("xmlSha256").also { require(it.matches(hash)) }
            val array = obj.getJSONArray("attachments")
            require(array.length() in 1..64)
            var total = xmlBytes.toLong()
            val seen = mutableSetOf<String>()
            val parts = (0 until array.length()).map {
                val item = array.getJSONObject(it)
                item.exactKeys("filename", "mime", "bytes", "sha256")
                val filename = item.text("filename")
                require(filename.matches(name) && seen.add(filename))
                val mime = item.text("mime")
                val extension = when (mime) {
                    "image/jpeg" -> ".jpg"
                    "image/png" -> ".png"
                    "image/webp" -> ".webp"
                    else -> throw IllegalArgumentException("Unsupported completion media")
                }
                require(filename.endsWith(extension))
                val bytes = item.count("bytes", 1_500_000)
                total += bytes
                require(total <= 12L * 1024 * 1024)
                val sha = item.text("sha256").also { require(it.matches(hash)) }
                ProtectedAttachmentEvidence(filename, mime, bytes, sha)
            }.sortedBy { it.filename }
            return RemoteCompletionEvidence(work, source, sourceInstance, target, form, visit,
                submitted, instance, xmlBytes, xmlHash, parts)
        }
    }
}
