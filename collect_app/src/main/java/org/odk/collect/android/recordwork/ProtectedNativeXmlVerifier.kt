package org.odk.collect.android.recordwork

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayInputStream

/** Verification evidence only; no raw answers, payload bytes or upload grant. */
internal data class ProtectedNativeXmlEvidence(
    val xmlSha256: String,
    val xmlBytes: Int,
    val repeatCount: Int,
    val mediaReferenceDigests: Map<Pair<String, String>, String>
)

internal data class ProtectedCommittedXmlEvidence(
    val eligibility: ProtectedFinalizationEligibility,
    val xml: ProtectedNativeXmlEvidence
)

/**
 * Independently parse native serializer output, checking exact flat tree,
 * lexical text, row order, instance identity and media reference bijection.
 * Opaque reference names are intentionally not actual attachment filenames.
 */
internal object ProtectedNativeXmlVerifier {
    fun verify(
        bytes: ByteArray,
        binding: QuarantinedWorkDraft,
        root: String,
        group: String,
        direct: Map<String, String>,
        rows: List<Map<String, String>>,
        references: Map<Pair<String, String>, String>
    ): ProtectedNativeXmlEvidence {
        require(bytes.size in 1..8_388_608)
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
        parser.setInput(ByteArrayInputStream(bytes), "UTF-8")
        fun nextTag(): Int {
            while (true) {
                val event = parser.nextToken()
                when (event) {
                    XmlPullParser.START_TAG, XmlPullParser.END_TAG, XmlPullParser.END_DOCUMENT -> return event
                    XmlPullParser.TEXT, XmlPullParser.IGNORABLE_WHITESPACE -> require(parser.text.isNullOrBlank())
                    else -> throw IllegalArgumentException("Unexpected XML token")
                }
            }
        }
        fun leaf(expected: String) {
            require(parser.attributeCount == 0)
            val name = parser.name
            val value = parser.nextText()
            require(value == expected && parser.eventType == XmlPullParser.END_TAG && parser.name == name)
        }
        fun fields(expected: Map<String, String>, parent: String) {
            val seen = mutableSetOf<String>()
            while (nextTag() == XmlPullParser.START_TAG) {
                val key = parent + "/" + parser.name
                require(seen.add(key))
                leaf(requireNotNull(expected[key]))
            }
            require(parser.eventType == XmlPullParser.END_TAG && seen == expected.keys)
        }

        require(nextTag() == XmlPullParser.START_TAG && parser.name == root)
        require(parser.getAttributeValue(null, "id") == binding.targetFormId &&
            parser.getAttributeValue(null, "version") == binding.targetVersion)
        require((0 until parser.attributeCount).all {
            parser.getAttributeName(it) in setOf("id", "version") &&
                parser.getAttributeNamespace(it).isNullOrEmpty()
        })
        var index = 0
        var meta = false
        val seen = mutableSetOf<String>()
        while (nextTag() == XmlPullParser.START_TAG) {
            when (parser.name) {
                group -> {
                    require(index < rows.size && parser.attributeCount == 0)
                    fields(rows[index++], "/$root/$group")
                    require(parser.name == group)
                }
                "meta" -> {
                    require(!meta && parser.attributeCount == 0)
                    meta = true
                    fields(mapOf("/$root/meta/instanceID" to binding.reservedInstanceId), "/$root/meta")
                    require(parser.name == "meta")
                }
                else -> {
                    val key = "/$root/" + parser.name
                    require(seen.add(key))
                    leaf(requireNotNull(direct[key]))
                }
            }
        }
        require(parser.eventType == XmlPullParser.END_TAG && parser.name == root &&
            meta && index == rows.size && seen == direct.keys)
        require(nextTag() == XmlPullParser.END_DOCUMENT)
        require(references.values.toSet().size == references.size &&
            references.values.all { it.matches(Regex("protected-[0-9a-f]{64}")) })
        return ProtectedNativeXmlEvidence(QuarantinedPrivateImageStore.digest(bytes), bytes.size,
            index, references.mapValues { QuarantinedWorkDraftVault.sha256(it.value) })
    }
}
