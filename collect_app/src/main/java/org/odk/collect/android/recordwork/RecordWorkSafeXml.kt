package org.odk.collect.android.recordwork

import android.util.Xml
import org.w3c.dom.Document
import org.xml.sax.InputSource
import org.xmlpull.v1.XmlPullParser
import java.io.StringReader
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Strict XML admission for authenticated record-work schema inspection.
 * Android's Harmony DOM factory does not implement desktop JAXP hardening
 * switches. Never silently ignore an unsupported security setting on an
 * unknown provider. On the known Android provider, preflight the same
 * immutable String with Android's non-DTD pull parser before DOM allocation.
 */
internal object RecordWorkSafeXml {
    private const val ANDROID_DOM =
        "org.apache.harmony.xml.parsers.DocumentBuilderFactoryImpl"
    private const val XINCLUDE = "http://www.w3.org/2001/XInclude"

    fun parse(xml: String, maxChars: Int, maxDepth: Int, maxElements: Int): Document {
        require(xml.isNotBlank() && xml.length <= maxChars) { "XML exceeds size limit" }
        // Input is already decoded Unicode; XML declaration names cannot be
        // escaped. Reject declarations before either parser processes them.
        require(!xml.contains("<!DOCTYPE", ignoreCase = true) &&
            !xml.contains("<!ENTITY", ignoreCase = true)) { "DTD/ENTITY forbidden" }
        val factory = DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = true
        factory.isValidating = false
        if (factory.javaClass.name == ANDROID_DOM) {
            preflightAndroid(xml, maxDepth, maxElements)
        } else {
            // Desktop/unit-test providers must honor ALL hardening features.
            factory.isXIncludeAware = false
            factory.isExpandEntityReferences = false
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false)
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
        }
        val document = factory.newDocumentBuilder().apply {
            setEntityResolver { _, _ -> throw IllegalArgumentException("External entities forbidden") }
        }.parse(InputSource(StringReader(xml)))
        require(document.doctype == null && document.documentElement != null) {
            "Unsafe XML document"
        }
        require(document.getElementsByTagNameNS(XINCLUDE, "*").length == 0) {
            "XInclude forbidden"
        }
        return document
    }

    private fun preflightAndroid(xml: String, maxDepth: Int, maxElements: Int) {
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL, false)
        require(!parser.getFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL)) {
            "DTD processing must be disabled"
        }
        parser.setInput(StringReader(xml))
        var count = 0
        while (true) {
            when (parser.nextToken()) {
                XmlPullParser.DOCDECL ->
                    throw IllegalArgumentException("DTD forbidden")
                XmlPullParser.START_TAG -> {
                    require(parser.depth <= maxDepth + 1 && ++count <= maxElements) {
                        "XML structure too complex"
                    }
                    require(parser.namespace != XINCLUDE) { "XInclude forbidden" }
                }
                XmlPullParser.END_DOCUMENT -> return
            }
        }
    }
}
