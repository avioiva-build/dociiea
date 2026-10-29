package com.omnidoc.app

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var store: RecentStore
    private lateinit var adapter: RecentsAdapter
    private var pendingViewHandled = false

    private val openDoc = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) onDocumentPicked(uri)
    }

    private val createDocx = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/vnd.openxmlformats-officedocument.wordprocessingml.document")
    ) { uri -> if (uri != null) onNewDocCreated(uri, "docx") }

    private val createTxt = registerForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")
    ) { uri -> if (uri != null) onNewDocCreated(uri, "txt") }

    private val createMd = registerForActivityResult(
        ActivityResultContracts.CreateDocument("text/markdown")
    ) { uri -> if (uri != null) onNewDocCreated(uri, "md") }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        store = RecentStore(this)

        findViewById<MaterialButton>(R.id.btnOpen).setOnClickListener {
            openDoc.launch(arrayOf("*/*"))
        }
        findViewById<MaterialButton>(R.id.btnNew).setOnClickListener { showNewDocDialog() }

        adapter = RecentsAdapter()
        val recycler = findViewById<RecyclerView>(R.id.recyclerRecents)
        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = adapter

        handleViewIntent(intent)
        refreshRecents()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleViewIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        refreshRecents()
    }

    @SuppressLint("SetTextI18n")
    private fun handleViewIntent(intent: Intent?) {
        if (intent?.action != Intent.ACTION_VIEW || pendingViewHandled) return
        val uri = intent.data ?: return
        pendingViewHandled = true
        try {
            contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (e: SecurityException) { /* not persistable; still fine for this session */ }
        routeTo(uri, FileUtils.queryDisplayName(this, uri), saveRecent = true)
    }

    private fun showNewDocDialog() {
        val kinds = arrayOf("Word document (.docx)", "Plain text (.txt)", "Markdown (.md)")
        AlertDialog.Builder(this)
            .setTitle("New document")
            .setItems(kinds) { _, which ->
                when (which) {
                    0 -> createDocx.launch("Untitled.docx")
                    1 -> createTxt.launch("Untitled.txt")
                    else -> createMd.launch("Untitled.md")
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun onDocumentPicked(uri: Uri) {
        val name = FileUtils.queryDisplayName(this, uri)
        routeTo(uri, name, saveRecent = true)
    }

    private fun onNewDocCreated(uri: Uri, kind: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val bytes: ByteArray = if (kind == "docx") {
                    com.omnidoc.app.docx.DocxWriter.createEmptyDocx()
                } else if (kind == "md") {
                    "# Untitled\n\n".toByteArray()
                } else {
                    ByteArray(0)
                }
                FileUtils.writeBytes(this@MainActivity, uri, bytes)
                withContext(Dispatchers.Main) {
                    val name = FileUtils.queryDisplayName(this@MainActivity, uri)
                    routeTo(uri, name, saveRecent = true)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "Could not create: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun routeTo(uri: Uri, displayName: String, saveRecent: Boolean) {
        val mime = contentResolver.getType(uri)
        val kind = FileUtils.kindOf(displayName, mime)
        if (saveRecent) store.touch(uri.toString(), displayName, kind.name)
        when (kind) {
            FileUtils.Kind.PDF -> startActivity(
                Intent(this, PdfViewerActivity::class.java)
                    .putExtra("uri", uri.toString()).putExtra("name", displayName)
            )
            FileUtils.Kind.DOCX -> startActivity(
                Intent(this, DocxEditorActivity::class.java)
                    .putExtra("uri", uri.toString()).putExtra("name", displayName)
            )
            FileUtils.Kind.TEXT -> startActivity(
                Intent(this, TextEditorActivity::class.java)
                    .putExtra("uri", uri.toString()).putExtra("name", displayName)
            )
            FileUtils.Kind.UNSUPPORTED -> {
                store.remove(uri.toString())
                Toast.makeText(
                    this,
                    "Unsupported format: $displayName\nSupported: .docx .pdf .txt .md and plain text/code files",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun refreshRecents() {
        val items = store.all()
        findViewById<TextView>(R.id.txtEmpty).isVisible = items.isEmpty()
        adapter.submit(items)
    }

    private inner class RecentsAdapter : RecyclerView.Adapter<RecentsAdapter.VH>() {
        private var items: List<RecentItem> = emptyList()

        fun submit(list: List<RecentItem>) {
            items = list
            notifyDataSetChanged()
        }

        inner class VH(val name: TextView, val meta: TextView, val card: com.google.android.material.card.MaterialCardView) :
            RecyclerView.ViewHolder(card)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_recent, parent, false)
                    as com.google.android.material.card.MaterialCardView
            return VH(v.findViewById(R.id.txtName), v.findViewById(R.id.txtMeta), v)
        }

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = items[position]
            holder.name.text = item.name
            holder.meta.text = when (item.kind) {
                "PDF" -> "PDF document" + if (item.lastPage > 0) " · stopped at page ${item.lastPage + 1}" else ""
                "DOCX" -> "Word document"
                else -> "Text document"
            }
            holder.card.setOnClickListener {
                routeTo(Uri.parse(item.uri), item.name, saveRecent = false)
            }
            holder.card.setOnLongClickListener {
                AlertDialog.Builder(this@MainActivity)
                    .setTitle(item.name)
                    .setItems(arrayOf("Remove from recent list")) { _, _ ->
                        store.remove(item.uri)
                        refreshRecents()
                    }
                    .show()
                true
            }
        }
    }
}
