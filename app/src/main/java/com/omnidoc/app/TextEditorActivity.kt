package com.omnidoc.app

import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.google.android.material.appbar.MaterialToolbar
import io.noties.markwon.Markwon
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class TextEditorActivity : AppCompatActivity() {

    private lateinit var uri: Uri
    private lateinit var docName: String
    private lateinit var editText: EditText
    private lateinit var scrollPreview: ScrollView
    private lateinit var txtPreview: TextView
    private lateinit var btnPreview: Button
    private var dirty = false
    private var isMarkdown = false
    private var previewOn = false
    private var pendingSaveText: String? = null
    private var suppressWatcher = false

    private val saveAsText = registerForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")
    ) { out ->
        val content = pendingSaveText
        if (out != null && content != null) {
            lifecycleScope.launch(Dispatchers.IO) {
                val ok = FileUtils.writeBytes(this@TextEditorActivity, out, content.toByteArray())
                withContext(Dispatchers.Main) { afterSave(ok, out) }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_text_editor)

        uri = Uri.parse(intent.getStringExtra("uri") ?: "")
        docName = intent.getStringExtra("name") ?: "Text"
        isMarkdown = FileUtils.extOf(docName) in setOf("md", "markdown")

        findViewById<MaterialToolbar>(R.id.toolbar).title = docName
        editText = findViewById(R.id.editText)
        scrollPreview = findViewById(R.id.scrollPreview)
        txtPreview = findViewById(R.id.txtPreview)
        btnPreview = findViewById(R.id.btnPreview)

        btnPreview.isVisible = isMarkdown
        if (!isMarkdown) btnPreview.visibility = View.GONE

        editText.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) { dirty = true }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })

        findViewById<Button>(R.id.btnFind).setOnClickListener { showFindReplace() }
        findViewById<Button>(R.id.btnCount).setOnClickListener { showWordCount() }
        if (isMarkdown) btnPreview.setOnClickListener { togglePreview() }
        findViewById<Button>(R.id.btnSave).setOnClickListener { save() }
        findViewById<Button>(R.id.btnSaveAs).setOnClickListener {
            pendingSaveText = editText.text.toString()
            saveAsText.launch(docName)
        }

        load()
    }

    private fun load() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val bytes = FileUtils.readAll(this@TextEditorActivity, uri)
                val text = String(bytes, Charsets.UTF_8)
                withContext(Dispatchers.Main) {
                    suppressWatcher = true
                    editText.setText(text)
                    editText.setSelection(text.length.coerceAtMost(0))
                    suppressWatcher = false
                    dirty = false
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@TextEditorActivity, "Cannot open: ${e.message}", Toast.LENGTH_LONG).show()
                    finish()
                }
            }
        }
    }

    private fun togglePreview() {
        previewOn = !previewOn
        scrollPreview.isVisible = previewOn
        btnPreview.text = if (previewOn) "Edit" else getString(R.string.preview)
        if (previewOn) {
            val markwon = Markwon.create(this)
            markwon.setMarkdown(txtPreview, editText.text.toString())
        }
    }

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
                val text = editText.text.toString()
                val hay = if (checkCase.isChecked) text else text.lowercase()
                val needle = if (checkCase.isChecked) find else find.lowercase()
                var count = 0
                var idx = hay.indexOf(needle)
                while (idx >= 0) { count++; idx = hay.indexOf(needle, idx + needle.length) }
                if (count > 0) {
                    val rep = editReplace.text.toString()
                    val sb = StringBuilder()
                    var pos = 0
                    idx = hay.indexOf(needle)
                    while (idx >= 0) {
                        sb.append(text, pos, idx).append(rep)
                        pos = idx + needle.length
                        idx = hay.indexOf(needle, pos)
                    }
                    sb.append(text, pos, text.length)
                    editText.setText(sb.toString())
                    Toast.makeText(this, "Replaced $count occurrence(s)", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "Not found", Toast.LENGTH_SHORT).show()
                }
                dialog.dismiss()
            }
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                val find = editFind.text.toString()
                if (find.isEmpty()) return@setOnClickListener
                val text = editText.text.toString()
                val hay = if (checkCase.isChecked) text else text.lowercase()
                val needle = if (checkCase.isChecked) find else find.lowercase()
                var count = 0
                var idx = hay.indexOf(needle)
                while (idx >= 0) { count++; idx = hay.indexOf(needle, idx + needle.length) }
                Toast.makeText(this, "$count occurrence(s) found", Toast.LENGTH_SHORT).show()
            }
        }
        dialog.show()
    }

    private fun showWordCount() {
        val text = editText.text.toString()
        val words = text.trim().split(Regex("\\s+")).count { it.isNotEmpty() }
        AlertDialog.Builder(this)
            .setTitle("Word count")
            .setMessage("Words: $words\nCharacters: ${text.length}\nLines: ${text.count { it == '\n' } + 1}")
            .setPositiveButton("OK", null)
            .show()
    }

    private fun save() {
        lifecycleScope.launch(Dispatchers.IO) {
            val content = editText.text.toString()
            val ok = try {
                FileUtils.backupOriginal(this@TextEditorActivity, uri, docName)
                FileUtils.writeBytes(this@TextEditorActivity, uri, content.toByteArray())
            } catch (e: Exception) { false }
            withContext(Dispatchers.Main) {
                if (ok) afterSave(true, uri)
                else {
                    pendingSaveText = content
                    saveAsText.launch(docName)
                }
            }
        }
    }

    private fun afterSave(ok: Boolean, target: Uri) {
        if (ok) {
            dirty = false
            uri = target
            docName = FileUtils.queryDisplayName(this, uri)
            findViewById<MaterialToolbar>(R.id.toolbar).title = docName
            RecentStore(this).touch(uri.toString(), docName, "TEXT")
            Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show()
        } else Toast.makeText(this, "Save failed", Toast.LENGTH_SHORT).show()
    }
}
