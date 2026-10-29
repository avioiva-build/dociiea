package com.omnidoc.app.docx

import android.graphics.BitmapFactory
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Converts editor HTML back into a valid .docx package.
 * Produces document.xml + styles.xml + numbering.xml + media images,
 * fully offline, no external template needed.
 */
object DocxWriter {

    // ---------------- models ----------------

    class RStyle(
        val bold: Boolean = false,
        val italic: Boolean = false,
        val underline: Boolean = false,
        val strike: Boolean = false,
        val color: String? = null,
        val highlight: String? = null,
        val sizeHalfPt: Int? = null,
        val font: Int = 0 // 0 default, 1 sans(Calibri), 2 serif(Cambria), 3 mono(Consolas)
    )

    class RText(val text: String, val style: RStyle)
    class RBr(val style: RStyle)
    class RImage(val assetIndex: Int, val style: RStyle)

    class ImageAsset(val mime: String, val bytes: ByteArray, val widthPx: Int, val heightPx: Int) {
        var relId: String = ""
        var mediaName: String = ""
    }

    class Para(
        val heading: Int = 0,
        val align: String? = null,
        val inList: Boolean = false,
        val listDepth: Int = 0,
        val ordered: Boolean = false,
        val isHr: Boolean = false,
        val runs: List<Any> = emptyList() // RText | RBr | RImage
    )

    class TableBlock(val rows: List<Row>) {
        class Row(val cells: List<Cell>)
        class Cell(val paras: List<Para>)
    }

    class Ctx {
        val images = ArrayList<ImageAsset>()
    }

    // ---------------- entry points ----------------

    fun createEmptyDocx(): ByteArray = fromHtml("<p><br></p>")

    fun fromHtml(html: String): ByteArray {
        val ctx = Ctx()
        val blocks = ArrayList<Any>() // Para | TableBlock
        val body = Jsoup.parse(html).body()
        walkBlocks(body, ctx, blocks, 0, false)
        if (blocks.isEmpty()) blocks.add(Para())
        return buildDocx(ctx, blocks)
    }

    // ---------------- HTML walking ----------------

    private fun walkBlocks(el: Element, ctx: Ctx, out: ArrayList<Any>, depth: Int, inList: Boolean) {
        for (node in el.childNodes()) {
            if (node is TextNode) {
                if (node.wholeText().trim().isNotEmpty()) {
                    out.add(Para(runs = listOf(RText(node.wholeText(), RStyle()))))
                }
                continue
            }
            if (node !is Element) continue
            when (node.tagName().lowercase()) {
                "p", "div" -> {
                    val hasBlockChild = node.children().any {
                        it.tagName() in setOf("p", "div", "ul", "ol", "table", "h1", "h2", "h3", "h4", "h5", "h6")
                    }
                    if (hasBlockChild) walkBlocks(node, ctx, out, depth, inList)
                    else out.add(paraFromInline(node, ctx, depth, inList, heading = 0))
                }
                "h1", "h2", "h3", "h4", "h5", "h6" ->
                    out.add(paraFromInline(node, ctx, depth, inList, heading = node.tagName()[1].digitToInt()))
                "ul", "ol" -> walkList(node, ctx, out, depth, ordered = node.tagName() == "ol")
                "table" -> out.add(tableFrom(node, ctx))
                "img" -> {
                    val runs = ArrayList<Any>()
                    inlineImage(node, ctx, RStyle(), runs)
                    if (runs.isNotEmpty()) out.add(Para(runs = runs))
                }
                "hr" -> out.add(Para(isHr = true))
                "br" -> out.add(Para())
                else -> walkBlocks(node, ctx, out, depth, inList)
            }
        }
    }

    private fun walkList(listEl: Element, ctx: Ctx, out: ArrayList<Any>, depth: Int, ordered: Boolean) {
        for (li in listEl.children()) {
            if (li.tagName() != "li") continue
            // inline content before nested lists
            val inlineNodes = ArrayList<Node>()
            val nestedLists = ArrayList<Element>()
            for (c in li.childNodes()) {
                if (c is Element && (c.tagName() == "ul" || c.tagName() == "ol")) nestedLists.add(c)
                else inlineNodes.add(c)
            }
            val runs = ArrayList<Any>()
            for (c in inlineNodes) walkInline(c, RStyle(), ctx, runs)
            out.add(Para(inList = true, listDepth = depth, ordered = ordered, runs = runs))
            for (nl in nestedLists) walkList(nl, ctx, out, depth + 1, ordered = nl.tagName() == "ol")
        }
    }

    private fun paraFromInline(el: Element, ctx: Ctx, depth: Int, inList: Boolean, heading: Int): Para {
        val runs = ArrayList<Any>()
        for (c in el.childNodes()) walkInline(c, RStyle(), ctx, runs)
        return Para(
            heading = heading,
            align = textAlignOf(el),
            inList = inList,
            listDepth = depth,
            ordered = false,
            runs = runs
        )
    }

    private fun tableFrom(tableEl: Element, ctx: Ctx): TableBlock {
        val rows = ArrayList<TableBlock.Row>()
        val trs = tableEl.select("tr")
        for (tr in trs) {
            val cells = ArrayList<TableBlock.Cell>()
            for (td in tr.children()) {
                if (td.tagName() !in setOf("td", "th")) continue
                val paras = ArrayList<Para>()
                val blockChildren = td.children().filter {
                    it.tagName() in setOf("p", "div", "ul", "ol", "table", "h1", "h2", "h3", "h4", "h5", "h6")
                }
                if (blockChildren.isEmpty()) {
                    val runs = ArrayList<Any>()
                    for (c in td.childNodes()) walkInline(c, RStyle(), ctx, runs)
                    paras.add(Para(runs = runs))
                } else {
                    walkBlocks(td, ctx, paras, 0, false)
                }
                if (paras.isEmpty()) paras.add(Para())
                cells.add(TableBlock.Cell(paras))
            }
            rows.add(TableBlock.Row(cells))
        }
        return TableBlock(rows)
    }

    private fun walkInline(node: Node, style: RStyle, ctx: Ctx, out: ArrayList<Any>) {
        if (node is TextNode) {
            val t = node.wholeText()
            if (t.isNotEmpty()) out.add(RText(t, style))
            return
        }
        if (node !is Element) return
        when (node.tagName().lowercase()) {
            "b", "strong" -> walkInlineChildren(node, style.copy(bold = true), ctx, out)
            "i", "em" -> walkInlineChildren(node, style.copy(italic = true), ctx, out)
            "u" -> walkInlineChildren(node, style.copy(underline = true), ctx, out)
            "s", "strike", "del" -> walkInlineChildren(node, style.copy(strike = true), ctx, out)
            "br" -> out.add(RBr(style))
            "img" -> inlineImage(node, ctx, style, out)
            "span", "font" -> out.takeLast(0).let { walkInlineChildren(node, applyStyleOverrides(node, style), ctx, out) }
            "a" -> walkInlineChildren(node, style, ctx, out)
            else -> walkInlineChildren(node, applyStyleOverrides(node, style), ctx, out)
        }
    }

    private fun walkInlineChildren(el: Element, style: RStyle, ctx: Ctx, out: ArrayList<Any>) {
        for (c in el.childNodes()) walkInline(c, style, ctx, out)
    }

    private fun applyStyleOverrides(el: Element, base: RStyle): RStyle {
        var s = base
        val style = el.attr("style")
        if (style.isNotEmpty()) {
            for (decl in style.split(";")) {
                val parts = decl.split(":", limit = 2)
                if (parts.size != 2) continue
                val key = parts[0].trim().lowercase()
                val value = parts[1].trim()
                when (key) {
                    "font-weight" -> if (value in setOf("bold", "700", "800", "900")) s = s.copy(bold = true)
                    "font-style" -> if (value == "italic") s = s.copy(italic = true)
                    "text-decoration" -> {
                        if (value.contains("underline")) s = s.copy(underline = true)
                        if (value.contains("line-through")) s = s.copy(strike = true)
                    }
                    "color" -> cssColor(value)?.let { s = s.copy(color = it) }
                    "background-color" -> cssColor(value)?.let { s = s.copy(highlight = it) }
                    "font-size" -> sizeToHalfPt(value)?.let { s = s.copy(sizeHalfPt = it) }
                    "font-family" -> s = s.copy(font = familyKey(value))
                }
            }
        }
        if (el.tagName() == "font") {
            el.attr("color")?.takeIf { it.isNotEmpty() }?.let { cssColor(it)?.let { c -> s = s.copy(color = c) } }
            el.attr("face")?.takeIf { it.isNotEmpty() }?.let { s = s.copy(font = familyKey(it)) }
            el.attr("size")?.toIntOrNull()?.let { n ->
                val hp = mapOf(1 to 15, 2 to 18, 3 to 20, 4 to 24, 5 to 26, 6 to 32, 7 to 42)[n.coerceIn(1, 7)]
                if (hp != null) s = s.copy(sizeHalfPt = hp)
            }
        }
        return s
    }

    private fun inlineImage(img: Element, ctx: Ctx, style: RStyle, out: ArrayList<Any>) {
        val src = img.attr("src")
        if (!src.startsWith("data:")) return
        val commaIdx = src.indexOf(",base64,")
        if (commaIdx < 0) return
        val mime = src.substring(5, commaIdx).ifEmpty { "image/png" }
        return try {
            val bytes = android.util.Base64.decode(src.substring(commaIdx + 8), android.util.Base64.DEFAULT)
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth > 0) {
                ctx.images.add(ImageAsset(mime, bytes, bounds.outWidth, bounds.outHeight))
                out.add(RImage(ctx.images.size - 1, style))
            }
        } catch (e: Exception) { }
    }

    private fun textAlignOf(el: Element): String? {
        val style = el.attr("style")
        val m = Regex("text-align\\s*:\\s*(\\w+)").find(style) ?: return null
        val v = m.groupValues[1].lowercase()
        return when (v) {
            "center" -> "center"
            "right" -> "right"
            "justify" -> "both"
            else -> null
        }
    }

    private fun sizeToHalfPt(v: String): Int? {
        val s = v.trim().lowercase()
        return when {
            s.endsWith("pt") -> (s.removeSuffix("pt").toFloatOrNull()?.times(2))?.roundToInt()
            s.endsWith("px") -> (s.removeSuffix("px").toFloatOrNull()?.times(1.5))?.roundToInt()
            else -> mapOf(
                "xx-small" to 15, "x-small" to 18, "small" to 20, "medium" to 24,
                "large" to 26, "x-large" to 32, "xx-large" to 42
            )[s]
        }
    }

    private fun familyKey(v: String): Int {
        val s = v.lowercase()
        return when {
            "mono" in s || "consol" in s || "courier" in s -> 3
            ("serif" in s && "sans" !in s) || "cambria" in s || "times" in s || "georgia" in s -> 2
            else -> 1
        }
    }

    private val cssNames = mapOf(
        "black" to "000000", "white" to "FFFFFF", "red" to "FF0000", "green" to "008000",
        "blue" to "0000FF", "yellow" to "FFFF00", "orange" to "FFA500", "purple" to "800080",
        "gray" to "808080", "grey" to "808080", "silver" to "C0C0C0", "navy" to "000080",
        "teal" to "008080", "aqua" to "00FFFF", "cyan" to "00FFFF", "fuchsia" to "FF00FF",
        "magenta" to "FF00FF", "lime" to "00FF00", "maroon" to "800000", "olive" to "808000"
    )

    private fun cssColor(v: String): String? {
        val s = v.trim().lowercase()
        return when {
            s.startsWith("#") && s.length == 4 -> s[1].toString() + s[1] + s[2] + s[2] + s[3] + s[3]
            s.startsWith("#") && s.length == 7 -> s.substring(1).uppercase()
            s.startsWith("#") && s.length == 9 -> s.substring(3).uppercase()
            s.startsWith("rgb(") -> {
                val nums = Regex("[0-9]+").findAll(s).map { it.value.toInt().coerceIn(0, 255).toString(16).padStart(2, '0') }.toList()
                if (nums.size >= 3) (nums[0] + nums[1] + nums[2]).uppercase() else null
            }
            else -> cssNames[s]?.let { it }
        }
    }

    // ---------------- DOCX package building ----------------

    private val fontNames = arrayOf("Calibri", "Calibri", "Cambria", "Consolas")

    private fun buildDocx(ctx: Ctx, blocks: List<Any>): ByteArray {
        ctx.images.forEachIndexed { i, img ->
            img.relId = "rIdImg${i + 1}"
            val ext = when (img.mime) {
                "image/png" -> "png"; "image/gif" -> "gif"; "image/bmp" -> "bmp"
                else -> "jpeg"
            }
            img.mediaName = "image${i + 1}.$ext"
        }

        val document = buildDocumentXml(blocks, ctx)
        val styles = buildStylesXml()
        val numbering = buildNumberingXml()
        val contentTypes = buildContentTypes(ctx)
        val rootRels = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/></Relationships>"""
        val docRels = buildDocumentRels(ctx)

        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("[Content_Types].xml")); zip.write(contentTypes.toByteArray()); zip.closeEntry()
            zip.putNextEntry(ZipEntry("_rels/.rels")); zip.write(rootRels.toByteArray()); zip.closeEntry()
            zip.putNextEntry(ZipEntry("word/document.xml")); zip.write(document.toByteArray()); zip.closeEntry()
            zip.putNextEntry(ZipEntry("word/_rels/document.xml.rels")); zip.write(docRels.toByteArray()); zip.closeEntry()
            zip.putNextEntry(ZipEntry("word/styles.xml")); zip.write(styles.toByteArray()); zip.closeEntry()
            zip.putNextEntry(ZipEntry("word/numbering.xml")); zip.write(numbering.toByteArray()); zip.closeEntry()
            ctx.images.forEachIndexed { i, img ->
                zip.putNextEntry(ZipEntry("word/media/${img.mediaName}")); zip.write(img.bytes); zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private fun buildDocumentRels(ctx: Ctx): String {
        val sb = StringBuilder()
        sb.append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">""")
        sb.append("""<Relationship Id="rIdStyles" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>""")
        sb.append("""<Relationship Id="rIdNumbering" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/numbering" Target="numbering.xml"/>""")
        ctx.images.forEach { img ->
            sb.append("<Relationship Id=\"${img.relId}\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/image\" Target=\"media/${img.mediaName}\"/>")
        }
        sb.append("</Relationships>")
        return sb.toString()
    }

    private fun buildContentTypes(ctx: Ctx): String {
        val sb = StringBuilder()
        sb.append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">""")
        sb.append("""<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>""")
        sb.append("""<Default Extension="xml" ContentType="application/xml"/>""")
        sb.append("""<Default Extension="png" ContentType="image/png"/>""")
        sb.append("""<Default Extension="jpeg" ContentType="image/jpeg"/>""")
        sb.append("""<Default Extension="gif" ContentType="image/gif"/>""")
        sb.append("""<Default Extension="bmp" ContentType="image/bmp"/>""")
        sb.append("""<Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>""")
        sb.append("""<Override PartName="/word/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml"/>""")
        sb.append("""<Override PartName="/word/numbering.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.numbering+xml"/>""")
        sb.append("</Types>")
        return sb.toString()
    }

    private fun buildDocumentXml(blocks: List<Any>, ctx: Ctx): String {
        val sb = StringBuilder()
        sb.append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships" xmlns:wp="http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing" xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" xmlns:pic="http://schemas.openxmlformats.org/drawingml/2006/picture"><w:body>""")
        for (block in blocks) {
            when (block) {
                is Para -> sb.append(paraXml(block, ctx))
                is TableBlock -> sb.append(tableXml(block, ctx))
            }
        }
        sb.append("""<w:sectPr><w:pgSz w:w="11906" w:h="16838"/><w:pgMar w:top="1440" w:right="1440" w:bottom="1440" w:left="1440" w:header="708" w:footer="708" w:gutter="0"/></w:sectPr>""")
        sb.append("</w:body></w:document>")
        return sb.toString()
    }

    private fun paraXml(p: Para, ctx: Ctx): String {
        val sb = StringBuilder("<w:p>")
        // pPr
        val pPr = StringBuilder()
        if (p.heading in 1..6) pPr.append("<w:pStyle w:val=\"Heading${p.heading}\"/>")
        else if (p.inList) pPr.append("<w:pStyle w:val=\"ListParagraph\"/>")
        if (p.inList) {
            val numId = if (p.ordered) 2 else 1
            val ilvl = p.listDepth.coerceIn(0, 8)
            pPr.append("<w:numPr><w:ilvl w:val=\"$ilvl\"/><w:numId w:val=\"$numId\"/></w:numPr>")
        }
        p.align?.let { pPr.append("<w:jc w:val=\"$it\"/>") }
        if (p.isHr) {
            pPr.append("<w:pBdr><w:bottom w:val=\"single\" w:sz=\"6\" w:space=\"1\" w:color=\"auto\"/></w:pBdr>")
        }
        if (pPr.isNotEmpty()) sb.append("<w:pPr>").append(pPr).append("</w:pPr>")
        // runs
        if (p.runs.isEmpty()) {
            sb.append("<w:r><w:rPr><w:sz w:val=\"2\"/></w:rPr><w:t xml:space=\"preserve\"> </w:t></w:r>")
        } else {
            for (run in p.runs) {
                when (run) {
                    is RText -> sb.append(runXml(run.text, run.style))
                    is RBr -> sb.append("<w:r>").append(rPrXml(run.style)).append("<w:br/></w:r>")
                    is RImage -> sb.append(imageXml(run.assetIndex, ctx))
                }
            }
        }
        sb.append("</w:p>")
        return sb.toString()
    }

    private fun rPrXml(s: RStyle): String {
        val sb = StringBuilder()
        s.font.takeIf { it != 0 }?.let {
            val name = fontNames[it]
            sb.append("<w:rFonts w:ascii=\"$name\" w:hAnsi=\"$name\" w:cs=\"$name\"/>")
        }
        if (s.bold) sb.append("<w:b/><w:bCs/>")
        if (s.italic) sb.append("<w:i/><w:iCs/>")
        if (s.strike) sb.append("<w:strike/>")
        s.color?.let { sb.append("<w:color w:val=\"${it.removePrefix("#")}\"/>") }
        s.sizeHalfPt?.let {
            sb.append("<w:sz w:val=\"$it\"/><w:szCs w:val=\"$it\"/>")
        }
        s.highlight?.let { sb.append("<w:highlight w:val=\"${nearestWordHighlight(it)}\"/>") }
        if (s.underline) sb.append("<w:u w:val=\"single\"/>")
        if (sb.isEmpty()) return ""
        return "<w:rPr>$sb</w:rPr>"
    }

    private fun runXml(text: String, style: RStyle): String {
        val clean = sanitize(text)
        if (clean.isEmpty()) return ""
        return "<w:r>${rPrXml(style)}<w:t xml:space=\"preserve\">${xmlEsc(clean)}</w:t></w:r>"
    }

    private fun imageXml(assetIndex: Int, ctx: Ctx): String {
        val img = ctx.images.getOrNull(assetIndex) ?: return ""
        val maxPx = 460
        var w = img.widthPx
        var h = img.heightPx
        if (w > maxPx) { h = (h.toLong() * maxPx / w).toInt(); w = maxPx }
        if (w <= 0) { w = 300; h = 200 }
        val cx = (w * 9525).toLong()
        val cy = (h * 9525).toLong()
        val idx = assetIndex + 1
        return "<w:r><w:drawing>" +
            "<wp:inline distT=\"0\" distB=\"0\" distL=\"0\" distR=\"0\">" +
            "<wp:extent cx=\"$cx\" cy=\"$cy\"/>" +
            "<wp:docPr id=\"$idx\" name=\"Picture $idx\"/>" +
            "<wp:cNvGraphicFramePr><a:graphicFrameLocks xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\" noChangeAspect=\"1\"/></wp:cNvGraphicFramePr>" +
            "<a:graphic xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\">" +
            "<a:graphicData uri=\"http://schemas.openxmlformats.org/drawingml/2006/picture\">" +
            "<pic:pic xmlns:pic=\"http://schemas.openxmlformats.org/drawingml/2006/picture\">" +
            "<pic:nvPicPr><pic:cNvPr id=\"$idx\" name=\"${img.mediaName}\"/><pic:cNvPicPr/></pic:nvPicPr>" +
            "<pic:blipFill><a:blip r:embed=\"${img.relId}\"/><a:stretch><a:fillRect/></a:stretch></pic:blipFill>" +
            "<pic:spPr><a:xfrm><a:off x=\"0\" y=\"0\"/><a:ext cx=\"$cx\" cy=\"$cy\"/></a:xfrm><a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom></pic:spPr>" +
            "</pic:pic></a:graphicData></a:graphic></wp:inline></w:drawing></w:r>"
    }

    private fun tableXml(t: TableBlock, ctx: Ctx): String {
        val cols = t.rows.maxOfOrNull { it.cells.size } ?: 0
        if (cols == 0) return ""
        val sb = StringBuilder("<w:tbl>")
        sb.append("<w:tblPr><w:tblStyle w:val=\"TableGrid\"/><w:tblW w:w=\"0\" w:type=\"auto\"/><w:tblBorders>")
        for (edge in listOf("top", "left", "bottom", "right", "insideH", "insideV")) {
            sb.append("<w:$edge w:val=\"single\" w:sz=\"4\" w:space=\"0\" w:color=\"auto\"/>")
        }
        sb.append("</w:tblBorders></w:tblPr>")
        sb.append("<w:tblGrid>")
        repeat(cols) { sb.append("<w:gridCol w:w=\"${11906 / cols}\"/>") }
        sb.append("</w:tblGrid>")
        for (row in t.rows) {
            sb.append("<w:tr>")
            for (cell in row.cells) {
                sb.append("<w:tc><w:tcPr><w:tcW w:w=\"${11906 / cols}\" w:type=\"dxa\"/></w:tcPr>")
                for (p in cell.paras) sb.append(paraXml(p, ctx))
                sb.append("</w:tc>")
            }
            sb.append("</w:tr>")
        }
        sb.append("</w:tbl>")
        return sb.toString()
    }

    // ---------------- styles / numbering ----------------

    private fun buildStylesXml(): String {
        val sb = StringBuilder()
        sb.append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<w:styles xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">""")
        sb.append("<w:docDefaults><w:rPrDefault><w:rPr><w:rFonts w:ascii=\"Calibri\" w:hAnsi=\"Calibri\" w:cs=\"Calibri\"/><w:sz w:val=\"22\"/><w:szCs w:val=\"22\"/></w:rPr></w:rPrDefault>")
        sb.append("<w:pPrDefault><w:pPr><w:spacing w:after=\"120\" w:line=\"252\" w:lineRule=\"auto\"/></w:pPr></w:pPrDefault></w:docDefaults>")
        sb.append("<w:style w:type=\"paragraph\" w:default=\"1\" w:styleId=\"Normal\"><w:name w:val=\"Normal\"/><w:qFormat/></w:style>")
        val sizes = intArrayOf(32, 26, 24, 22, 20, 20)
        for (h in 1..6) {
            sb.append("<w:style w:type=\"paragraph\" w:styleId=\"Heading$h\"><w:name w:val=\"heading $h\"/><w:basedOn w:val=\"Normal\"/><w:next w:val=\"Normal\"/><w:qFormat/>" +
                "<w:pPr><w:keepNext/><w:spacing w:before=\"240\" w:after=\"80\"/></w:pPr>" +
                "<w:rPr><w:b/><w:color w:val=\"2F5496\"/><w:sz w:val=\"${sizes[h - 1]}\"/><w:szCs w:val=\"${sizes[h - 1]}\"/></w:rPr></w:style>")
        }
        sb.append("<w:style w:type=\"paragraph\" w:styleId=\"ListParagraph\"><w:name w:val=\"List Paragraph\"/><w:basedOn w:val=\"Normal\"/><w:pPr><w:ind w:left=\"720\"/></w:pPr></w:style>")
        sb.append("<w:style w:type=\"table\" w:styleId=\"TableGrid\"><w:name w:val=\"Table Grid\"/><w:tblPr><w:tblBorders>")
        for (edge in listOf("top", "left", "bottom", "right", "insideH", "insideV")) {
            sb.append("<w:$edge w:val=\"single\" w:sz=\"4\" w:space=\"0\" w:color=\"auto\"/>")
        }
        sb.append("</w:tblBorders></w:tblPr></w:style>")
        sb.append("</w:styles>")
        return sb.toString()
    }

    private fun buildNumberingXml(): String {
        val sb = StringBuilder()
        sb.append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<w:numbering xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">""")
        // abstract 0: bullets, abstract 1: decimal
        for (abstractId in 0..1) {
            sb.append("<w:abstractNum w:abstractNumId=\"$abstractId\"><w:multiLevelType w:val=\"hybridMultilevel\"/>")
            for (lvl in 0..8) {
                val indent = 720 + lvl * 360
                if (abstractId == 0) {
                    val glyph = when (lvl % 3) {
                        0 -> "<w:rFonts w:ascii=\"Symbol\" w:hAnsi=\"Symbol\" w:hint=\"default\"/>"
                        else -> ""
                    }
                    val lvlText = when (lvl % 3) {
                        0 -> "&#61623;"
                        1 -> "o"
                        else -> "&#61607;"
                    }
                    val font = when (lvl % 3) {
                        0 -> "Symbol"
                        1 -> "Courier New"
                        else -> "Wingdings"
                    }
                    sb.append("<w:lvl w:ilvl=\"$lvl\"><w:start w:val=\"1\"/><w:numFmt w:val=\"bullet\"/><w:lvlText w:val=\"$lvlText\"/><w:lvlJc w:val=\"left\"/>" +
                        "<w:pPr><w:ind w:left=\"$indent\" w:hanging=\"360\"/></w:pPr>" +
                        "<w:rPr><w:rFonts w:ascii=\"$font\" w:hAnsi=\"$font\" w:hint=\"default\"/></w:rPr></w:lvl>")
                } else {
                    sb.append("<w:lvl w:ilvl=\"$lvl\"><w:start w:val=\"1\"/><w:numFmt w:val=\"decimal\"/><w:lvlText w:val=\"%${lvl + 1}.\"/><w:lvlJc w:val=\"left\"/>" +
                        "<w:pPr><w:ind w:left=\"$indent\" w:hanging=\"360\"/></w:pPr></w:lvl>")
                }
            }
            sb.append("</w:abstractNum>")
        }
        sb.append("<w:num w:numId=\"1\"><w:abstractNumId w:val=\"0\"/></w:num>")
        sb.append("<w:num w:numId=\"2\"><w:abstractNumId w:val=\"1\"/></w:num>")
        sb.append("</w:numbering>")
        return sb.toString()
    }

    // ---------------- utilities ----------------

    private val wordHighlights = listOf(
        "black" to 0x000000, "blue" to 0x0000FF, "cyan" to 0x00FFFF,
        "darkBlue" to 0x00008B, "darkCyan" to 0x008B8B, "darkGray" to 0x555555,
        "darkGreen" to 0x006400, "darkMagenta" to 0x8B008B, "darkRed" to 0x8B0000,
        "darkYellow" to 0x808000, "gray" to 0x808080, "green" to 0x008000,
        "lightGray" to 0xC0C0C0, "magenta" to 0xFF00FF, "red" to 0xFF0000,
        "white" to 0xFFFFFF, "yellow" to 0xFFFF00
    )

    private fun nearestWordHighlight(hex: String): String {
        val rgb = hex.removePrefix("#").toLongOrNull(16)?.toInt() ?: return "yellow"
        val r = (rgb shr 16) and 0xFF; val g = (rgb shr 8) and 0xFF; val b = rgb and 0xFF
        var best = "yellow"; var bestDist = Int.MAX_VALUE
        for ((name, c) in wordHighlights) {
            val dr = r - ((c shr 16) and 0xFF); val dg = g - ((c shr 8) and 0xFF); val db = b - (c and 0xFF)
            val d = dr * dr + dg * dg + db * db
            if (d < bestDist) { bestDist = d; best = name }
        }
        return best
    }

    private fun sanitize(s: String): String {
        val sb = StringBuilder(s.length)
        for (c in s) {
            if (c == '\t' || c == '\n' || c == '\r' || c.code >= 0x20) sb.append(c)
        }
        return sb.toString()
    }

    private fun xmlEsc(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}
