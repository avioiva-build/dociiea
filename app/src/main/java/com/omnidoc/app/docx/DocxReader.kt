package com.omnidoc.app.docx

import android.util.Base64
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream

/**
 * Lightweight .docx reader: converts the parts of a Word document that
 * OmniDoc's "Standard" editing tier supports into editor HTML.
 * Supported: paragraphs, headings 1-6, bold/italic/underline/strike,
 * colour, size, font family, alignment, bullet/numbered lists, simple tables,
 * inline images, hyperlinks.
 */
class DocxReader {

    private val media = HashMap<String, Pair<String, ByteArray>>() // path -> mime+bytes
    private val rels = HashMap<String, Rel>()                      // rId -> rel
    private val numberingBullet = HashMap<String, Boolean>()       // numId -> isBullet

    class Rel(val target: String, val external: Boolean)

    fun toHtml(bytes: ByteArray): String {
        var documentXml: String? = null
        var relsXml: String? = null
        var numberingXml: String? = null

        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                val name = entry.name.replace('\\', '/')
                when {
                    name == "word/document.xml" -> documentXml = zip.readBytes().toString(Charsets.UTF_8)
                    name == "word/_rels/document.xml.rels" -> relsXml = zip.readBytes().toString(Charsets.UTF_8)
                    name == "word/numbering.xml" -> numberingXml = zip.readBytes().toString(Charsets.UTF_8)
                    name.startsWith("word/media/") -> {
                        val data = zip.readBytes()
                        val mime = mimeFor(name)
                        media[name] = mime to data
                    }
                }
                entry = zip.nextEntry
            }
        }

        relsXml?.let { parseRels(it) }
        numberingXml?.let { parseNumbering(it) }

        val xml = documentXml ?: throw IllegalArgumentException("Not a valid .docx (document.xml missing)")
        return parseBody(xml)
    }

    private fun mimeFor(path: String): String = when (path.substringAfterLast('.').lowercase()) {
        "png" -> "image/png"
        "gif" -> "image/gif"
        "bmp" -> "image/bmp"
        "svg" -> "image/svg+xml"
        else -> "image/jpeg"
    }

    private fun parseRels(xml: String) {
        val p = newParser(xml)
        while (p.eventType != XmlPullParser.END_DOCUMENT) {
            if (p.eventType == XmlPullParser.START_TAG && local(p.name) == "Relationship") {
                val id = attr(p, "Id")
                val target = attr(p, "Target")
                val mode = attr(p, "TargetMode")
                if (id != null && target != null) {
                    rels[id] = Rel(target, mode.equals("External", true))
                }
            }
            p.next()
        }
    }

    private fun parseNumbering(xml: String) {
        val p = newParser(xml)
        var currentNumId: String? = null
        var currentAbstract: String? = null
        val abstractFmt = HashMap<String, String>() // abstractId -> numFmt of lvl0
        var inLvl0 = false
        while (p.eventType != XmlPullParser.END_DOCUMENT) {
            if (p.eventType == XmlPullParser.START_TAG) {
                when (local(p.name)) {
                    "num" -> currentNumId = attr(p, "numId")
                    "abstractNumId" -> currentAbstract = attr(p, "val")
                    "lvl" -> inLvl0 = (attr(p, "ilvl") == "0")
                    "numFmt" -> {
                        if (inLvl0 && currentAbstract != null) {
                            abstractFmt[currentAbstract] = attr(p, "val") ?: "bullet"
                            inLvl0 = false
                        }
                    }
                }
            } else if (p.eventType == XmlPullParser.END_TAG) {
                when (local(p.name)) {
                    "num" -> {
                        val nid = currentNumId
                        val aid = currentAbstract
                        if (nid != null && aid != null) {
                            numberingBullet[nid] = (abstractFmt[aid] ?: "bullet") == "bullet"
                        }
                        currentNumId = null; currentAbstract = null
                    }
                }
            }
            p.next()
        }
    }

    // ---------- document body ----------

    private fun parseBody(xml: String): String {
        val p = newParser(xml)
        val sb = StringBuilder()
        val listStack = ArrayDeque<String>() // "ul" | "ol"
        // advance to w:body
        while (p.eventType != XmlPullParser.END_DOCUMENT) {
            if (p.eventType == XmlPullParser.START_TAG && local(p.name) == "body") break
            p.next()
        }
        while (p.eventType != XmlPullParser.END_DOCUMENT) {
            if (p.eventType == XmlPullParser.END_TAG && local(p.name) == "body") break
            if (p.eventType == XmlPullParser.START_TAG) {
                when (local(p.name)) {
                    "p" -> {
                        val html = parseParagraph(p, inListContext = false)
                        manageLists(sb, listStack, html.listDepth, html.listType)
                        sb.append(html.html)
                    }
                    "tbl" -> {
                        closeLists(sb, listStack)
                        sb.append(parseTable(p))
                    }
                    else -> p.skipElement()
                }
            } else {
                p.next()
            }
        }
        closeLists(sb, listStack)
        return sb.toString()
    }

    private fun manageLists(sb: StringBuilder, stack: ArrayDeque<String>, depth: Int, type: String?) {
        val wantedDepth = if (type == null) 0 else depth + 1
        while (stack.size > wantedDepth) {
            sb.append("</").append(stack.removeLast()).append(">")
        }
        if (type != null) {
            while (stack.size < wantedDepth) {
                val t = if (type == "ol") "ol" else "ul"
                stack.addLast(t)
                sb.append("<").append(t).append(">")
            }
            // fix type mismatch at current level
            val top = stack.lastOrNull()
            if (top != null && ((type == "ol" && top != "ol") || (type == "ul" && top != "ul"))) {
                sb.append("</").append(stack.removeLast()).append(">")
                stack.addLast(if (type == "ol") "ol" else "ul")
                sb.append("<").append(if (type == "ol") "ol" else "ul").append(">")
            }
        }
    }

    private fun closeLists(sb: StringBuilder, stack: ArrayDeque<String>) {
        while (stack.isNotEmpty()) {
            sb.append("</").append(stack.removeLast()).append(">")
        }
    }

    class ParaHtml(val html: String, val listDepth: Int, val listType: String?)

    private fun parseParagraph(p: XmlPullParser, inListContext: Boolean): ParaHtml {
        var align: String? = null
        var heading: Int = 0
        var numId: String? = null
        var ilvl = 0
        val runs = StringBuilder()

        while (!(p.eventType == XmlPullParser.END_TAG && local(p.name) == "p")) {
            if (p.eventType == XmlPullParser.START_TAG) {
                when (local(p.name)) {
                    "pPr" -> {
                        val props = readParagraphProps(p)
                        align = props.first; heading = props.second
                        numId = props.third; ilvl = props.fourth
                    }
                    "r" -> runs.append(parseRun(p))
                    "hyperlink" -> {
                        val relId = findAttr(p, "id")
                        val target = rels[relId]?.takeIf { it.external }?.target
                        val inner = parseRunChildren(p)
                        runs.append(if (target != null) {
                            "<a href=\"" + esc(target) + "\">" + inner + "</a>"
                        } else inner)
                    }
                    "bookmarkStart", "bookmarkEnd", "proofErr" -> p.skipElement()
                    else -> p.skipElement()
                }
            } else {
                p.next()
            }
        }
        p.next() // consume </p>

        var inner = runs.toString()
        if (numId != null && inListContext.not()) {
            // paragraph carries list info but we're not inside a list element
            // (table cells) -> render with a marker prefix
            val marker = if (numberingBullet[numId] == false) "1. " else "•  "
            inner = "$marker$inner"
        }

        val style = if (align != null && align != "left") " style=\"text-align:$align\"" else ""
        val html = if (heading in 1..6) "<h$heading$style>$inner</h$heading>"
                   else if (inner.isEmpty()) "<p><br></p>"
                   else "<p$style>$inner</p>"
        val listType = numId?.let { if (numberingBullet[it] == false) "ol" else "ul" }
        return ParaHtml(html, if (numId != null) ilvl else 0, listType)
    }

    private fun readParagraphProps(p: XmlPullParser): Quintet<String?, Int, String?, Int, Int?> {
        var align: String? = null
        var heading = 0
        var numId: String? = null
        var ilvl = 0
        var outlineLvl: Int? = null
        while (!(p.eventType == XmlPullParser.END_TAG && local(p.name) == "pPr")) {
            if (p.eventType == XmlPullParser.START_TAG) {
                when (local(p.name)) {
                    "jc" -> align = attr(p, "val")
                    "pStyle" -> {
                        val v = attr(p, "val") ?: ""
                        heading = headingFromStyle(v)
                    }
                    "numPr" -> {
                        // read children numId / ilvl
                        while (!(p.eventType == XmlPullParser.END_TAG && local(p.name) == "numPr")) {
                            if (p.eventType == XmlPullParser.START_TAG) {
                                when (local(p.name)) {
                                    "numId" -> numId = p.nextTextSafe()
                                    "ilvl" -> ilvl = p.nextTextSafe()?.toIntOrNull() ?: 0
                                    else -> p.skipElement()
                                }
                            } else p.next()
                        }
                    }
                    "outlineLvl" -> outlineLvl = (attr(p, "val")?.toIntOrNull() ?: 9).let { if (it in 0..5) it + 1 else 0 }
                    else -> p.skipElement()
                }
            } else p.next()
        }
        p.next() // </pPr>
        val head = if (heading != 0) heading else (outlineLvl ?: 0)
        return Quintet(align, head, numId, ilvl, null)
    }

    private fun headingFromStyle(v: String): Int {
        val m = Regex("(?i)heading\\s*([1-6])").find(v)
        if (m != null) return m.groupValues[1].toInt()
        if (v.equals("Title", true)) return 1
        return 0
    }

    private fun parseRun(p: XmlPullParser): String {
        var bold = false; var italic = false; var underline = false; var strike = false
        var color: String? = null; var highlight: String? = null
        var sizeHalfPt: Int? = null; var fontFamily: String? = null
        val text = StringBuilder()

        while (!(p.eventType == XmlPullParser.END_TAG && local(p.name) == "r")) {
            if (p.eventType == XmlPullParser.START_TAG) {
                when (local(p.name)) {
                    "rPr" -> {
                        while (!(p.eventType == XmlPullParser.END_TAG && local(p.name) == "rPr")) {
                            if (p.eventType == XmlPullParser.START_TAG) {
                                when (local(p.name)) {
                                    "b" -> bold = attr(p, "val") != "0" && attr(p, "val") != "false"
                                    "i" -> italic = attr(p, "val") != "0" && attr(p, "val") != "false"
                                    "u" -> underline = attr(p, "val") != "none"
                                    "strike" -> strike = attr(p, "val") != "0" && attr(p, "val") != "false"
                                    "color" -> color = normHex(attr(p, "val"))
                                    "highlight" -> highlight = wordHighlightHex(attr(p, "val"))
                                    "sz" -> sizeHalfPt = attr(p, "val")?.toIntOrNull()
                                    "rFonts" -> fontFamily = attr(p, "ascii") ?: attr(p, "hAnsi")
                                    else -> p.skipElement()
                                }
                            } else p.next()
                        }
                        p.next() // </rPr>
                    }
                    "t" -> text.append(esc(p.nextTextSafe() ?: ""))
                    "tab" -> { p.consumeSelfClosing(); text.append("&nbsp;&nbsp;&nbsp;&nbsp;") }
                    "br", "cr" -> { p.consumeSelfClosing(); text.append("<br>") }
                    "sym" -> p.skipElement()
                    "drawing" -> text.append(parseDrawing(p))
                    "pict" -> text.append(parseDrawing(p))
                    "noBreakHyphen" -> { p.consumeSelfClosing(); text.append("-") }
                    else -> p.skipElement()
                }
            } else p.next()
        }
        p.next() // </r>

        if (text.isEmpty()) return ""
        val css = StringBuilder()
        if (bold) css.append("font-weight:bold;")
        if (italic) css.append("font-style:italic;")
        if (underline && strike) css.append("text-decoration:underline line-through;")
        else if (underline) css.append("text-decoration:underline;")
        else if (strike) css.append("text-decoration:line-through;")
        color?.let { css.append("color:").append(it).append(";") }
        highlight?.let { css.append("background-color:").append(it).append(";") }
        sizeHalfPt?.let { css.append("font-size:").append(it / 2.0).append("pt;") }
        fontFamily?.let { css.append("font-family:'").append(it.replace('\'', ' ')).append("',sans-serif;") }
        return if (css.isEmpty()) text.toString()
        else "<span style=\"" + css + "\">" + text + "</span>"
    }

    private fun parseRunChildren(p: XmlPullParser): String {
        // For w:hyperlink: parse runs until </hyperlink> without consuming it here
        val sb = StringBuilder()
        while (!(p.eventType == XmlPullParser.END_TAG && local(p.name) == "hyperlink")) {
            if (p.eventType == XmlPullParser.START_TAG) {
                when (local(p.name)) {
                    "r" -> sb.append(parseRun(p))
                    else -> p.skipElement()
                }
            } else p.next()
        }
        p.next() // </hyperlink>
        return sb.toString()
    }

    private fun parseDrawing(p: XmlPullParser): String {
        var embed: String? = null
        var depth = 0
        while (true) {
            when (p.eventType) {
                XmlPullParser.START_TAG -> {
                    depth++
                    val e = findAttr(p, "embed")
                    if (embed == null && e != null) embed = e
                }
                XmlPullParser.END_TAG -> {
                    depth--
                    if (depth == 0 && local(p.name) == "drawing") break
                    if (depth == 0 && local(p.name) == "pict") break
                }
                XmlPullParser.END_DOCUMENT -> return ""
            }
            p.next()
        }
        p.next()
        val rel = rels[embed] ?: return ""
        if (rel.external) return ""
        val target = rel.target.removePrefix("../").removePrefix("./").let {
            if (it.startsWith("media/")) "word/$it" else if (it.startsWith("word/")) it else "word/$it"
        }
        val (mime, data) = media[target] ?: return ""
        val b64 = Base64.encodeToString(data, Base64.NO_WRAP)
        return "<img src=\"data:$mime;base64,$b64\">"
    }

    private fun parseTable(p: XmlPullParser): String {
        val rows = StringBuilder()
        var cols = 0
        var firstRow = true
        while (!(p.eventType == XmlPullParser.END_TAG && local(p.name) == "tbl")) {
            if (p.eventType == XmlPullParser.START_TAG) {
                when (local(p.name)) {
                    "tr" -> {
                        val cells = StringBuilder()
                        var cellCount = 0
                        while (!(p.eventType == XmlPullParser.END_TAG && local(p.name) == "tr")) {
                            if (p.eventType == XmlPullParser.START_TAG) {
                                when (local(p.name)) {
                                    "tc" -> {
                                        val cellHtml = StringBuilder()
                                        while (!(p.eventType == XmlPullParser.END_TAG && local(p.name) == "tc")) {
                                            if (p.eventType == XmlPullParser.START_TAG) {
                                                when (local(p.name)) {
                                                    "p" -> cellHtml.append(parseParagraph(p, inListContext = true).html)
                                                    "tbl" -> cellHtml.append(parseTable(p))
                                                    else -> p.skipElement()
                                                }
                                            } else p.next()
                                        }
                                        p.next() // </tc>
                                        cells.append("<td>").append(cellHtml).append("</td>")
                                        cellCount++
                                    }
                                    else -> p.skipElement()
                                }
                            } else p.next()
                        }
                        p.next() // </tr>
                        rows.append("<tr>").append(cells).append("</tr>")
                        if (firstRow) { cols = cellCount; firstRow = false }
                    }
                    else -> p.skipElement()
                }
            } else p.next()
        }
        p.next() // </tbl>
        return "<table><tbody>$rows</tbody></table>"
    }

    // ---------- small helpers ----------

    private class Quintet<A, B, C, D, E>(val first: A, val second: B, val third: C, val fourth: D, val fifth: E)

    private fun newParser(xml: String): XmlPullParser {
        val f = XmlPullParserFactory.newInstance()
        f.isNamespaceAware = false
        val p = f.newPullParser()
        p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        p.setInput(ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8)), null)
        p.next()
        return p
    }

    private fun local(name: String?): String = (name ?: "").substringAfterLast(':')

    private fun attr(p: XmlPullParser, localName: String): String? {
        for (i in 0 until p.attributeCount) {
            if (local(p.getAttributeName(i)) == localName) return p.getAttributeValue(i)
        }
        return null
    }

    private fun findAttr(p: XmlPullParser, suffix: String): String? {
        for (i in 0 until p.attributeCount) {
            val n = p.getAttributeName(i) ?: continue
            if (n.endsWith(suffix) || local(n) == suffix) return p.getAttributeValue(i)
        }
        return null
    }

    private fun XmlPullParser.nextTextSafe(): String? {
        // positioned at START_TAG; read text then consume END_TAG
        var result: String? = null
        while (true) {
            val ev = next()
            if (ev == XmlPullParser.TEXT) result = text
            else if (ev == XmlPullParser.END_TAG) break
            else if (ev == XmlPullParser.START_TAG) skipElement()
            else if (ev == XmlPullParser.END_DOCUMENT) break
        }
        next()
        return result
    }

    private fun XmlPullParser.consumeSelfClosing() {
        // positioned at START_TAG of an empty element; consume through END_TAG if present
        val ev = next()
        if (ev == XmlPullParser.END_TAG) next()
    }

    private fun XmlPullParser.skipElement() {
        // positioned at START_TAG; skip the whole subtree
        var depth = 1
        while (depth > 0) {
            val ev = next()
            when (ev) {
                XmlPullParser.START_TAG -> depth++
                XmlPullParser.END_TAG -> depth--
                XmlPullParser.END_DOCUMENT -> return
            }
        }
        next()
    }

    private fun esc(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private fun normHex(v: String?): String? {
        if (v == null || v.equals("auto", true)) return null
        var s = v.removePrefix("#")
        if (s.length == 3) s = s.map { "$it$it" }.joinToString("")
        if (s.length == 8) s = s.substring(2)
        return "#${s.uppercase()}"
    }

    private val highlightMap = mapOf(
        "black" to "#000000", "blue" to "#0000FF", "cyan" to "#00FFFF",
        "darkBlue" to "#00008B", "darkCyan" to "#008B8B", "darkGray" to "#555555",
        "darkGreen" to "#006400", "darkMagenta" to "#8B008B", "darkRed" to "#8B0000",
        "darkYellow" to "#808000", "gray" to "#808080", "green" to "#008000",
        "lightGray" to "#C0C0C0", "magenta" to "#FF00FF", "red" to "#FF0000",
        "white" to "#FFFFFF", "yellow" to "#FFFF00"
    )

    private fun wordHighlightHex(v: String?): String? = v?.let { highlightMap[it] }
}
