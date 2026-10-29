package com.omnidoc.app

import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.appbar.MaterialToolbar
import com.omnidoc.app.docx.DocxReader
import com.omnidoc.app.docx.DocxWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.jsoup.nodes.TextNode
import java.io.File
import java.security.MessageDigest

class DocxEditorActivity : AppCompatActivity(), RichEditorView.Listener {

    private lateinit var editor: RichEditorView
    private lateinit var uri: Uri
    private lateinit var docName: String
    private var dirty = false
    private var draftJob: Job? = null
    private var pendingSaveBytes: ByteArray? = null
    private var loaded = false

    private lateinit var btnBold: Button
    private lateinit var btnItalic: Button
    private lateinit var btnUnderline: Button
    private lateinit var btnStrike: Button

    private val saveAsDocx = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/vnd.openxmlformats-officedocument.wordprocessingml.document")
    ) { uriOut ->
        val bytes = pendingSaveBytes
        if (uriOut != null && bytes != null) {
            lifecycleScope.launch(Dispatchers.IO) {
                val ok = FileUtils.writeBytes(this@DocxEditorActivity, uriOut, bytes)
                withContext(Dispatchers.Main) { afterSave(ok, uriOut) }
            }
        }
    }

    private val pickImage = registerForActivityResult(ActivityResultContracts.GetContent()) { uriIn ->
        if (uriIn != null) {
            lifecycleScope.launch(Dispatchers.IO) {
                val img = FileUtils.downscaleImage(this@DocxEditorActivity, uriIn)
                withContext(Dispatchers.Main) {
                    if (img != null) editor.insertImage(FileUtils.bytesToDataUri(img.first, img.second))
                    else Toast.makeText(this@DocxEditorActivity, "Could not read image", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_docx_editor)

        uri = Uri.parse(intent.getStringExtra("uri") ?: "")
        docName = intent.getStringExtra("name") ?: "Document.docx"
        findViewById<MaterialToolbar>(R.id.toolbar).title = docName

        editor = findViewById(R.id.editor)
        editor.listener = this

        wireToolbar()
        loadDocument()
    }

    private fun loadDocument() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val bytes = FileUtils.readAll(this@DocxEditorActivity, uri)
                val html = DocxReader().toHtml(bytes)
                withContext(Dispatchers.Main) {
                    editor.setHtml(html)
                    loaded = true
                    maybeOfferDraftRestore()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@DocxEditorActivity, "Cannot open: ${e.message}", Toast.LENGTH_LONG).show()
                    finish()
                }
            }
        }
    }

    // ---------- draft (crash recovery) ----------

    private fun draftFile(): File {
        val digest = MessageDigest.getInstance("SHA-256").digest(uri.toString().toByteArray())
        val key = digest.joinToString("") { "%02x".format(it) }.take(24)
        return File(File(filesDir, "drafts").apply { mkdirs() }, "$key.html")
    }

    private fun maybeOfferDraftRestore() {
        val draft = draftFile()
        if (!draft.exists() || draft.length() == 0L) return
        val saved = try { draft.readText() } catch (e: Exception) { return }
        AlertDialog.Builder(this)
            .setTitle("Unsaved changes found")
            .setMessage("A recovery draft from your last session exists. Restore it?")
            .setPositiveButton("Restore") { _, _ -> editor.setHtml(saved) }
            .setNegativeButton("Discard") { _, _ -> draft.delete() }
            .show()
    }

    override fun onContentChanged() {
        if (!loaded) return
        dirty = true
        draftJob?.cancel()
        draftJob = lifecycleScope.launch {
            delay(2500)
            saveDraft()
        }
    }

    private fun saveDraft() {
        lifecycleScope.launch {
            val html = try { editor.getHtml() } catch (e: Exception) { return@launch }
            withContext(Dispatchers.IO) { runCatching { draftFile().writeText(html) } }
        }
    }

    // ---------- toolbar ----------

    private fun wireToolbar() {
        btnBold = findViewById(R.id.btnBold)
        btnItalic = findViewById(R.id.btnItalic)
        btnUnderline = findViewById(R.id.btnUnderline)
        btnStrike = findViewById(R.id.btnStrike)
        btnBold.setOnClickListener { editor.bold() }
        btnItalic.setOnClickListener { editor.italic() }
        btnUnderline.setOnClickListener { editor.underline() }
        btnStrike.setOnClickListener { editor.strike() }

        findViewById<Button>(R.id.btnBullets).setOnClickListener { editor.bullets() }
        findViewById<Button>(R.id.btnNumbers).setOnClickListener { editor.numbers() }
        findViewById<Button>(R.id.btnImage).setOnClickListener { pickImage.launch("image/*") }
        findViewById<Button>(R.id.btnTable).setOnClickListener { editor.insertTable() }

        setupSpinner(R.id.spinHeading, listOf("Body", "Title", "Heading 1", "Heading 2", "Heading 3", "Heading 4", "Heading 5", "Heading 6")) { pos ->
            editor.setHeading(if (pos == 0) 0 else pos - 1)
        }
        setupSpinner(R.id.spinFont, listOf("Font", "Sans", "Serif", "Mono")) { pos ->
            editor.setFontFamily(when (pos) {
                1 -> "sans-serif"; 2 -> "serif"; 3 -> "monospace"; else -> "sans-serif"
            })
        }
        setupSpinner(R.id.spinSize, listOf("Size", "Tiny", "Small", "Normal", "Large", "X-Large", "Huge")) { pos ->
            if (pos > 0) editor.setFontSize((pos).toString()) // css size 1..6
        }

        findViewById<Button>(R.id.btnTextColor).setOnClickListener { showColorPicker(false) }
        findViewById<Button>(R.id.btnHighlight).setOnClickListener { showColorPicker(true) }

        findViewById<Button>(R.id.btnAlignLeft).setOnClickListener { editor.alignLeft() }
        findViewById<Button>(R.id.btnAlignCenter).setOnClickListener { editor.alignCenter() }
        findViewById<Button>(R.id.btnAlignRight).setOnClickListener { editor.alignRight() }
        findViewById<Button>(R.id.btnAlignJustify).setOnClickListener { editor.alignJustify() }

        findViewById<Button>(R.id.btnUndo).setOnClickListener { editor.undo() }
        findViewById<Button>(R.id.btnRedo).setOnClickListener { editor.redo() }
        findViewById<Button>(R.id.btnFind).setOnClickListener { showFindReplace() }
        findViewById<Button>(R.id.btnCount).setOnClickListener { showWordCount() }
        findViewById<Button>(R.id.btnSave).setOnClickListener { save() }
        findViewById<Button>(R.id.btnSaveAs).setOnClickListener { saveAs() }
    }

    private fun setupSpinner(id: Int, items: List<String>, onPick: (Int) -> Unit) {
        val sp = findViewById<Spinner>(id)
        sp.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, items)
        sp.setSelection(0, false)
        sp.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, idLong: Long) {
                if (position != 0) {
                    onPick(position)
                    sp.setSelection(0, false)
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun showColorPicker(highlight: Boolean) {
        val colors = intArrayOf(
            0xFF0000.toInt(), 0xFF6600.toInt(), 0xFFCC00.toInt(), 0x009900.toInt(),
            0x0066CC.toInt(), 0x6600CC.toInt(), 0x000000.toInt(), 0x555555.toInt(),
            0x999999.toInt(), 0xCCCCCC.toInt(), 0x006699.toInt(), 0x993366.toInt(),
            0x336699.toInt(), 0x669933.toInt(), 0x996633.toInt(), 0x3366CC.toInt()
        )
        val container = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 16, 24, 16) }
        var row: LinearLayout? = null
        var dialogRef: AlertDialog? = null
        colors.forEachIndexed { i, c ->
            if (i % 8 == 0) {
                row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
                container.addView(row)
            }
            val sw = Button(this)
            sw.layoutParams = LinearLayout.LayoutParams(80, 80).apply { setMargins(8, 8, 8, 8) }
            sw.backgroundTintList = android.content.res.ColorStateList.valueOf(c)
            sw.setOnClickListener {
                if (highlight) editor.setHighlight(String.format("#%06X", c and 0xFFFFFF))
                else editor.setTextColor(String.format("#%06X", c and 0xFFFFFF))
                dialogRef?.dismiss()
            }
            row!!.addView(sw)
        }
        dialogRef = AlertDialog.Builder(this)
            .setTitle(if (highlight) "Highlight colour" else "Text colour")
            .setView(container)
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ---------- find & replace ----------

    private fun showFindReplace() {
        val view = layoutInflater.inflate(R.layout.dialog_find_replace, null)
        val editFind = view.findViewById<EditText>(R.id.editFind)
        val editReplace = view.findViewById<EditText>(R.id.editReplace)
        val checkCase = view.findViewById<android.widget.CheckBox>(R.id.checkCase)

        val dialog = AlertDialog.Builder(this)
            .setTitle("Find & replace")
            .setView(view)
            .setPositiveButton("Replace all", null)
            .setNeutralButton("Count", null)
            .setNegativeButton("Cancel", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val find = editFind.text.toString()
                if (find.isEmpty()) return@setOnClickListener
                lifecycleScope.launch {
                    val html = editor.getHtml()
                    val (newHtml, count) = withContext(Dispatchers.Default) { replaceInHtml(html, find, editReplace.text.toString(), checkCase.isChecked) }
                    if (count > 0) {
                        editor.setHtml(newHtml)
                        Toast.makeText(this@DocxEditorActivity, "Replaced $count occurrence(s)", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this@DocxEditorActivity, "Not found", Toast.LENGTH_SHORT).show()
                    }
                    dialog.dismiss()
                }
            }
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                val find = editFind.text.toString()
                if (find.isEmpty()) return@setOnClickListener
                lifecycleScope.launch {
                    val html = editor.getHtml()
                    val (_, count) = withContext(Dispatchers.Default) { replaceInHtml(html, find, null, checkCase.isChecked) }
                    Toast.makeText(this@DocxEditorActivity, "$count occurrence(s) found", Toast.LENGTH_SHORT).show()
                }
            }
        }
        dialog.show()
    }

    private fun replaceInHtml(html: String, find: String, replace: String?, matchCase: Boolean): Pair<String, Int> {
        var count = 0
        val doc = Jsoup.parse(html)
        val needle = if (matchCase) find else find.lowercase()
        for (node in doc.body().descendants()) {
            if (node is TextNode) {
                val text = node.wholeText ?: continue
                val hay = if (matchCase) text else text.lowercase()
                var idx = hay.indexOf(needle)
                if (idx < 0 || replace == null) {
                    while (idx >= 0) { count++; idx = hay.indexOf(needle, idx + needle.length) }
                    continue
                }
                val sb = StringBuilder()
                var pos = 0
                while (idx >= 0) {
                    count++
                    sb.append(text, pos, idx).append(replace)
                    pos = idx + needle.length
                    idx = hay.indexOf(needle, pos)
                }
                sb.append(text, pos, text.length)
                node.text(sb.toString())
            }
        }
        val out = doc.body().html()
        return out to count
    }

    private fun showWordCount() {
        lifecycleScope.launch {
            val html = editor.getHtml()
            val text = withContext(Dispatchers.Default) { Jsoup.parse(html).wholeText() }
            val words = text.trim().split(Regex("\\s+")).count { it.isNotEmpty() }
            AlertDialog.Builder(this@DocxEditorActivity)
                .setTitle("Word count")
                .setMessage("Words: $words\nCharacters (with spaces): ${text.length}")
                .setPositiveButton("OK", null)
                .show()
        }
    }

    // ---------- save ----------

    private fun save() {
        lifecycleScope.launch {
            val html = editor.getHtml()
            val bytes = withContext(Dispatchers.Default) { DocxWriter.fromHtml(html) }
            val ok = withContext(Dispatchers.IO) {
                FileUtils.backupOriginal(this@DocxEditorActivity, uri, docName)
                FileUtils.writeBytes(this@DocxEditorActivity, uri, bytes)
            }
            if (ok) afterSave(true, uri)
            else {
                pendingSaveBytes = bytes
                saveAsDocx.launch(docName)
            }
        }
    }

    private fun saveAs() {
        lifecycleScope.launch {
            val html = editor.getHtml()
            pendingSaveBytes = withContext(Dispatchers.Default) { DocxWriter.fromHtml(html) }
            saveAsDocx.launch(docName)
        }
    }

    private fun afterSave(ok: Boolean, target: Uri) {
        if (ok) {
            dirty = false
            draftFile().delete()
            uri = target
            docName = FileUtils.queryDisplayName(this, uri)
            findViewById<MaterialToolbar>(R.id.toolbar).title = docName
            RecentStore(this).touch(uri.toString(), docName, "DOCX")
            Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "Save failed", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onSelectionStateChanged(bold: Boolean, italic: Boolean, underline: Boolean, strike: Boolean) {
        btnBold.alpha = if (bold) 0.45f else 1f
        btnItalic.alpha = if (italic) 0.45f else 1f
        btnUnderline.alpha = if (underline) 0.45f else 1f
        btnStrike.alpha = if (strike) 0.45f else 1f
    }

    override fun onPause() {
        super.onPause()
        if (loaded && dirty) saveDraft()
    }
}
