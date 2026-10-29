package com.omnidoc.app

import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.util.LruCache
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.card.MaterialCardView
import com.tom_roush.pdfbox.pdmodel.PDDocument
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors
import kotlin.math.roundToInt

class PdfEditorActivity : AppCompatActivity() {

    private class PageEntry(val src: Int, val pageIndex: Int) {
        var extraRotation: Int = 0
        var deleted: Boolean = false
    }

    private lateinit var uri: Uri
    private lateinit var docName: String
    private lateinit var store: RecentStore

    private val sourceDocs = ArrayList<PDDocument>()      // 0 = base, then appended
    private val sourcePfds = ArrayList<ParcelFileDescriptor?>()
    private val sourceRenderers = ArrayList<PdfRenderer?>() // thumb renderers
    private var entries = ArrayList<PageEntry>()
    private val selection = LinkedHashSet<Int>()

    private lateinit var recycler: RecyclerView
    private lateinit var adapter: ThumbsAdapter
    private lateinit var txtSelection: TextView
    private val renderExecutor = Executors.newSingleThreadExecutor()
    private lateinit var cache: LruCache<String, Bitmap>
    private var pendingSaveBytes: ByteArray? = null

    private val saveAsPdf = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/pdf")
    ) { out ->
        val bytes = pendingSaveBytes
        if (out != null && bytes != null) {
            lifecycleScope.launch(Dispatchers.IO) {
                val ok = FileUtils.writeBytes(this@PdfEditorActivity, out, bytes)
                withContext(Dispatchers.Main) { afterWrite(ok) }
            }
        }
    }

    private val pickPdfToAppend = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { picked ->
        if (picked != null) appendPdf(picked)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_pdf_editor)
        store = RecentStore(this)

        uri = Uri.parse(intent.getStringExtra("uri") ?: "")
        docName = intent.getStringExtra("name") ?: "PDF"
        findViewById<MaterialToolbar>(R.id.toolbar).title = docName
        txtSelection = findViewById(R.id.txtSelection)

        cache = object : LruCache<String, Bitmap>(32 * 1024) {
            override fun sizeOf(key: String, value: Bitmap) = value.byteCount / 1024
        }

        recycler = findViewById(R.id.recyclerThumbs)
        adapter = ThumbsAdapter()
        recycler.layoutManager = GridLayoutManager(this, 3)
        recycler.adapter = adapter

        wireButtons()

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val size = FileUtils.querySize(this@PdfEditorActivity, uri)
                val big = size > 80L * 1024 * 1024
                withContext(Dispatchers.Main) {
                    if (big) {
                        AlertDialog.Builder(this@PdfEditorActivity)
                            .setTitle("Large PDF")
                            .setMessage("This PDF is ${FileUtils.humanSize(size.toLong())}. Page editing loads the whole document into memory and may be slow or fail. Continue?")
                            .setPositiveButton("Continue") { _, _ -> loadBase() }
                            .setNegativeButton("Cancel") { _, _ -> finish() }
                            .show()
                    } else {
                        loadBase()
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { fail(e) }
            }
        }
    }

    private fun fail(e: Exception) {
        Toast.makeText(this, "Cannot open PDF: ${e.message}", Toast.LENGTH_LONG).show()
        finish()
    }

    private fun loadBase() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val doc = contentResolver.openInputStream(uri)!!.use { PDDocument.load(it) }
                sourceDocs.add(doc)
                val pfd = contentResolver.openFileDescriptor(uri, "r")
                val renderer = PdfRenderer(pfd)
                sourcePfds.add(pfd)
                sourceRenderers.add(renderer)
                rebuildEntriesFromSources()
                withContext(Dispatchers.Main) { adapter.notifyDataSetChanged(); updateStatus() }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { fail(e) }
            }
        }
    }

    private fun rebuildEntriesFromSources() {
        entries = ArrayList()
        selection.clear()
        for (s in sourceDocs.indices) {
            for (i in 0 until sourceDocs[s].numberOfPages) {
                entries.add(PageEntry(s, i))
            }
        }
    }

    private fun wireButtons() {
        findViewById<TextView>(R.id.btnRotate).setOnClickListener {
            if (selection.isEmpty()) { toast("Select pages first"); return@setOnClickListener }
            for (pos in selection) entries[pos].extraRotation = (entries[pos].extraRotation + 90) % 360
            adapter.notifyDataSetChanged()
        }
        findViewById<TextView>(R.id.btnUp).setOnClickListener { moveSelection(-1) }
        findViewById<TextView>(R.id.btnDown).setOnClickListener { moveSelection(1) }
        findViewById<TextView>(R.id.btnDelete).setOnClickListener {
            if (selection.isEmpty()) { toast("Select pages first"); return@setOnClickListener }
            AlertDialog.Builder(this)
                .setMessage("Delete ${selection.size} selected page(s)?")
                .setPositiveButton("Delete") { _, _ ->
                    for (pos in selection) entries[pos].deleted = true
                    selection.clear()
                    adapter.notifyDataSetChanged(); updateStatus()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
        findViewById<TextView>(R.id.btnUndoAll).setOnClickListener {
            AlertDialog.Builder(this)
                .setMessage("Reset all page edits (deletions, rotations, order)? Appended pages stay.")
                .setPositiveButton("Reset") { _, _ ->
                    rebuildEntriesFromSources()
                    adapter.notifyDataSetChanged(); updateStatus()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
        findViewById<TextView>(R.id.btnAppend).setOnClickListener {
            pickPdfToAppend.launch(arrayOf("application/pdf"))
        }
        findViewById<TextView>(R.id.btnExtract).setOnClickListener {
            if (selection.isEmpty()) { toast("Select pages to extract"); return@setOnClickListener }
            buildFinalPdf(onlySelected = true) { bytes ->
                pendingSaveBytes = bytes
                val base = docName.removeSuffix(".pdf")
                saveAsPdf.launch("$base-extracted.pdf")
            }
        }
        findViewById<TextView>(R.id.btnSave).setOnClickListener { save() }
    }

    private fun moveSelection(dir: Int) {
        if (selection.isEmpty()) { toast("Select pages first"); return }
        val positions = selection.sorted()
        if (dir < 0) {
            for (pos in positions) {
                if (pos - 1 >= 0 && pos - 1 !in selection) {
                    java.util.Collections.swap(entries, pos, pos - 1)
                    selection.remove(pos); selection.add(pos - 1)
                }
            }
        } else {
            for (pos in positions.reversed()) {
                if (pos + 1 < entries.size && pos + 1 !in selection) {
                    java.util.Collections.swap(entries, pos, pos + 1)
                    selection.remove(pos); selection.add(pos + 1)
                }
            }
        }
        adapter.notifyDataSetChanged()
    }

    private fun appendPdf(newUri: Uri) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val doc = contentResolver.openInputStream(newUri)!!.use { PDDocument.load(it) }
                val pfd = contentResolver.openFileDescriptor(newUri, "r")
                val renderer = PdfRenderer(pfd)
                val srcIndex = sourceDocs.size
                sourceDocs.add(doc); sourcePfds.add(pfd); sourceRenderers.add(renderer)
                val added = doc.numberOfPages
                for (i in 0 until added) entries.add(PageEntry(srcIndex, i))
                withContext(Dispatchers.Main) {
                    adapter.notifyDataSetChanged(); updateStatus()
                    toast("Appended $added page(s)")
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { toast("Append failed: ${e.message}") }
            }
        }
    }

    private fun buildFinalPdf(onlySelected: Boolean, onDone: (ByteArray) -> Unit) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val chosen = entries.withIndex().filter { !it.value.deleted && (!onlySelected || it.index in selection) }
                if (chosen.isEmpty()) { withContext(Dispatchers.Main) { toast("Nothing to save") }; return@launch }
                val newDoc = PDDocument()
                for ((_, e) in chosen) {
                    val page = sourceDocs[e.src].getPage(e.pageIndex)
                    val imported = newDoc.importPage(page)
                    if (e.extraRotation % 360 != 0) {
                        imported.rotation = (page.rotation + e.extraRotation) % 360
                    }
                }
                val out = java.io.ByteArrayOutputStream()
                newDoc.save(out)
                newDoc.close()
                val bytes = out.toByteArray()
                withContext(Dispatchers.Main) { onDone(bytes) }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { toast("Build failed: ${e.message}") }
            }
        }
    }

    private fun save() {
        buildFinalPdf(onlySelected = false) { bytes ->
            lifecycleScope.launch(Dispatchers.IO) {
                val ok = try {
                    FileUtils.backupOriginal(this@PdfEditorActivity, uri, docName)
                    FileUtils.writeBytes(this@PdfEditorActivity, uri, bytes)
                } catch (e: Exception) { false }
                withContext(Dispatchers.Main) {
                    if (ok) {
                        Toast.makeText(this@PdfEditorActivity, "Saved — reopening to see changes", Toast.LENGTH_SHORT).show()
                        store.touch(uri.toString(), docName, "PDF")
                        finish()
                    } else {
                        pendingSaveBytes = bytes
                        saveAsPdf.launch(docName)
                    }
                }
            }
        }
    }

    private fun afterWrite(ok: Boolean) {
        if (ok) {
            Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show()
            finish()
        } else toast("Save failed")
    }

    private fun updateStatus() {
        val alive = entries.count { !it.deleted }
        val sel = selection.size
        txtSelection.text = buildString {
            append("$alive page(s)")
            if (entries.any { it.deleted }) append(" · ${entries.count { it.deleted }} deleted")
            append(if (sel > 0) " · $sel selected" else " · tap pages to select")
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    // ---------- adapter ----------

    private inner class ThumbsAdapter : RecyclerView.Adapter<ThumbsAdapter.VH>() {

        inner class VH(val card: MaterialCardView, val img: android.widget.ImageView, val num: TextView, val rot: TextView) :
            RecyclerView.ViewHolder(card)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_pdf_thumb, parent, false)
                    as MaterialCardView
            return VH(v, v.findViewById(R.id.imgThumb), v.findViewById(R.id.txtPageNum), v.findViewById(R.id.txtRotated))
        }

        override fun getItemCount(): Int = entries.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val entry = entries[position]
            val selected = position in selection
            holder.card.strokeColor = if (selected)
                ContextCompat.getColor(this@PdfEditorActivity, R.color.editor_ink) else 0x00000000
            holder.card.alpha = if (entry.deleted) 0.35f else 1f
            holder.num.text = if (entry.deleted) "p.${position + 1} (deleted)" else "p.${position + 1}"
            if (entry.extraRotation != 0) {
                holder.rot.visibility = android.view.View.VISIBLE
                holder.rot.text = "${entry.extraRotation}°"
            } else holder.rot.visibility = android.view.View.GONE

            holder.card.setOnClickListener {
                if (position in selection) selection.remove(position) else selection.add(position)
                notifyItemChanged(position); updateStatus()
            }

            val key = "t-${entry.src}-${entry.pageIndex}-${entry.extraRotation}"
            val cached = cache.get(key)
            if (cached != null) {
                holder.img.setImageBitmap(cached)
                return
            }
            holder.img.setImageBitmap(null)
            val stamp = position
            renderExecutor.execute {
                try {
                    val bmp = renderThumb(entry.src, entry.pageIndex, entry.extraRotation)
                    cache.put(key, bmp)
                    runOnUiThread {
                        if (holder.absoluteAdapterPosition == stamp) holder.img.setImageBitmap(bmp)
                    }
                } catch (e: Exception) { }
            }
        }
    }

    private fun renderThumb(srcIndex: Int, pageIndex: Int, extraRotation: Int): Bitmap {
        val renderer = sourceRenderers[srcIndex] ?: throw IllegalStateException("no renderer")
        lateinit var bmp: Bitmap
        synchronized(renderer) {
            val page = renderer.openPage(pageIndex)
            val maxSide = 420
            val scale = maxSide.toFloat() / maxOf(page.width, page.height)
            val w = (page.width * scale).roundToInt().coerceAtLeast(1)
            val h = (page.height * scale).roundToInt().coerceAtLeast(1)
            bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            bmp.eraseColor(android.graphics.Color.WHITE)
            val mtx = Matrix().apply { setScale(scale, scale) }
            page.render(bmp, mtx, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            page.close()
        }
        if (extraRotation != 0) {
            val mtx = Matrix().apply { postRotate(extraRotation.toFloat()) }
            val rotated = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, mtx, true)
            if (rotated != bmp) bmp.recycle()
            return rotated
        }
        return bmp
    }

    override fun onDestroy() {
        super.onDestroy()
        renderExecutor.shutdownNow()
        for (r in sourceRenderers) runCatching { r?.close() }
        for (p in sourcePfds) runCatching { p?.close() }
        for (d in sourceDocs) runCatching { d.close() }
        sourceRenderers.clear(); sourcePfds.clear(); sourceDocs.clear()
    }
}
