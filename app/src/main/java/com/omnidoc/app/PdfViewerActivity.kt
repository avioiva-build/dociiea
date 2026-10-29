package com.omnidoc.app

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.util.LruCache
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem
import com.tom_roush.pdfbox.text.PDFTextStripper
import android.graphics.pdf.PdfRenderer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors

class PdfViewerActivity : AppCompatActivity() {

    private lateinit var uri: Uri
    private lateinit var docName: String
    private lateinit var store: RecentStore

    private var pfd: ParcelFileDescriptor? = null
    private var renderer: PdfRenderer? = null
    private var pageCount = 0
    private lateinit var pageDims: List<Pair<Int, Int>>
    private var targetWidth = 1080

    private lateinit var recycler: RecyclerView
    private lateinit var adapter: PagesAdapter
    private lateinit var txtPageInfo: TextView

    private val renderExecutor = Executors.newSingleThreadExecutor()
    private lateinit var cache: LruCache<String, Bitmap>

    private var pdfBoxDoc: PDDocument? = null
    private var nightMode = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_pdf_viewer)
        store = RecentStore(this)

        uri = Uri.parse(intent.getStringExtra("uri") ?: "")
        docName = intent.getStringExtra("name") ?: "PDF"
        findViewById<MaterialToolbar>(R.id.toolbar).title = docName
        txtPageInfo = findViewById(R.id.txtPageInfo)

        targetWidth = minOf(resources.displayMetrics.widthPixels, 2048)
        val maxMem = (Runtime.getRuntime().maxMemory() / 1024 / 4).coerceAtMost(48 * 1024).toInt()
        cache = object : LruCache<String, Bitmap>(maxMem) {
            override fun sizeOf(key: String, value: Bitmap) = value.byteCount / 1024
        }

        recycler = findViewById(R.id.recyclerPages)
        adapter = PagesAdapter()
        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = adapter
        recycler.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                val lm = rv.layoutManager as LinearLayoutManager
                val pos = lm.findFirstVisibleItemPosition()
                if (pos in 0 until pageCount) {
                    txtPageInfo.text = "Page ${pos + 1} / $pageCount"
                }
            }
        })

        wireButtons()
        openDocument()
    }

    private fun openDocument() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                pfd = contentResolver.openFileDescriptor(uri, "r")
                renderer = PdfRenderer(pfd)
                pageCount = renderer!!.pageCount
                // collect page dimensions up-front so heights are known instantly
                val dims = ArrayList<Pair<Int, Int>>(pageCount)
                for (i in 0 until pageCount) {
                    val p = renderer!!.openPage(i)
                    dims.add(p.width to p.height)
                    p.close()
                }
                pageDims = dims
                withContext(Dispatchers.Main) {
                    findViewById<TextView>(R.id.txtLoading).text = ""
                    adapter.notifyDataSetChanged()
                    val last = store.all().firstOrNull { it.uri == uri.toString() }
                    val pos = (last?.lastPage ?: 0).coerceIn(0, (pageCount - 1).coerceAtLeast(0))
                    (recycler.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(pos, 0)
                    txtPageInfo.text = "Page ${pos + 1} / $pageCount"
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@PdfViewerActivity, "Cannot open PDF: ${e.message}", Toast.LENGTH_LONG).show()
                    finish()
                }
            }
        }
    }

    private fun renderPage(index: Int): Bitmap {
        val r = renderer ?: throw IllegalStateException("closed")
        val (pw, ph) = pageDims[index]
        var w = targetWidth
        var h = (w.toLong() * ph / pw).toInt()
        if (h > 4096) { h = 4096; w = (h.toLong() * pw / ph).toInt() }
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(Color.WHITE)
        synchronized(r) {
            val page = r.openPage(index)
            val scale = w.toFloat() / pw
            val mtx = Matrix().apply { setScale(scale, scale) }
            page.render(bmp, mtx, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            page.close()
        }
        return bmp
    }

    private fun wireButtons() {
        findViewById<TextView>(R.id.btnSearch).setOnClickListener { showSearch() }
        findViewById<TextView>(R.id.btnOutline).setOnClickListener { showOutline() }
        findViewById<TextView>(R.id.btnGoto).setOnClickListener { showGoto() }
        findViewById<TextView>(R.id.btnNight).setOnClickListener {
            nightMode = !nightMode
            adapter.notifyDataSetChanged()
        }
        findViewById<TextView>(R.id.btnEditPages).setOnClickListener {
            startActivity(
                Intent(this, PdfEditorActivity::class.java)
                    .putExtra("uri", uri.toString()).putExtra("name", docName)
            )
        }
    }

    // ---------- search ----------

    private fun ensurePdfBox(onReady: (PDDocument) -> Unit) {
        pdfBoxDoc?.let { onReady(it); return }
        val dlg = AlertDialog.Builder(this).setTitle("Preparing…").setMessage("Reading document").setCancelable(false).show()
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val doc = contentResolver.openInputStream(uri)?.use { PDDocument.load(it) }
                pdfBoxDoc = doc
                withContext(Dispatchers.Main) {
                    dlg.dismiss()
                    if (doc != null) onReady(doc)
                    else Toast.makeText(this@PdfViewerActivity, "Could not read PDF text", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { dlg.dismiss(); Toast.makeText(this@PdfViewerActivity, "Error: ${e.message}", Toast.LENGTH_LONG).show() }
            }
        }
    }

    private var searchJob: Job? = null

    private fun showSearch() {
        val input = EditText(this).apply { hint = "Search text" }
        val dialog = AlertDialog.Builder(this)
            .setTitle("Search in PDF")
            .setView(input)
            .setPositiveButton("Find", null)
            .setNegativeButton("Cancel", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val q = input.text.toString().trim()
                if (q.isEmpty()) return@setOnClickListener
                dialog.dismiss()
                ensurePdfBox { doc -> runSearch(doc, q) }
            }
        }
        dialog.show()
    }

    private fun runSearch(doc: PDDocument, query: String) {
        val progress = AlertDialog.Builder(this)
            .setTitle("Searching…")
            .setMessage("Page 1 / $pageCount")
            .setNegativeButton("Cancel") { _, _ -> searchJob?.cancel() }
            .show()
        searchJob = lifecycleScope.launch(Dispatchers.IO) {
            val results = ArrayList<Triple<Int, String, Int>>() // page, snippet, countOnPage
            try {
                val stripper = PDFTextStripper()
                for (page in 1..pageCount) {
                    ensureActive()
                    stripper.setStartPage(page); stripper.setEndPage(page)
                    val text = stripper.getText(doc).replace('\n', ' ')
                    var idx = text.indexOf(query, ignoreCase = true)
                    var count = 0
                    var firstSnippet = ""
                    while (idx >= 0) {
                        count++
                        if (firstSnippet.isEmpty()) {
                            val from = (idx - 30).coerceAtLeast(0)
                            firstSnippet = ("…" + text.substring(from, (idx + query.length + 40).coerceAtMost(text.length)) + "…")
                        }
                        idx = text.indexOf(query, idx + query.length, ignoreCase = true)
                    }
                    if (count > 0) results.add(Triple(page - 1, firstSnippet, count))
                    withContext(Dispatchers.Main) { progress.setMessage("Page $page / $pageCount") }
                }
            } catch (e: Exception) { /* cancelled */ }
            withContext(Dispatchers.Main) {
                progress.dismiss()
                if (results.isEmpty()) {
                    Toast.makeText(this@PdfViewerActivity, "No matches found", Toast.LENGTH_SHORT).show()
                } else {
                    showResultsDialog(results)
                }
            }
        }
    }

    private fun showResultsDialog(results: List<Triple<Int, String, Int>>) {
        val titles = results.map { "Page ${it.first + 1}  (${it.third} match${if (it.third > 1) "es" else ""})\n${it.second}" }
        AlertDialog.Builder(this)
            .setTitle("${results.size} page(s) with matches")
            .setItems(titles.toTypedArray()) { _, which -> jumpTo(results[which].first) }
            .setNegativeButton("Close", null)
            .show()
    }

    // ---------- outline ----------

    private fun showOutline() {
        ensurePdfBox { doc ->
            val items = ArrayList<Triple<Int, Int, String>>() // depth, pageIndex, title
            val outline = doc.documentOutline
            if (outline != null) collectOutline(outline, 0, doc, items)
            if (items.isEmpty()) {
                Toast.makeText(this, "This PDF has no outline/bookmarks", Toast.LENGTH_SHORT).show()
                return@ensurePdfBox
            }
            val labels = items.map { "  ".repeat(it.first) + it.third }
            AlertDialog.Builder(this)
                .setTitle("Outline")
                .setItems(labels.toTypedArray()) { _, which -> jumpTo(items[which].second) }
                .setNegativeButton("Close", null)
                .show()
        }
    }

    private fun collectOutline(item: PDDocumentOutline, depth: Int, doc: PDDocument, out: ArrayList<Triple<Int, Int, String>>) {
        collectOutlineItems(item.firstChild, depth, doc, out)
    }

    private fun collectOutlineItems(item: PDOutlineItem?, depth: Int, doc: PDDocument, out: ArrayList<Triple<Int, Int, String>>) {
        var cur = item
        while (cur != null) {
            try {
                val title = cur.title ?: "(untitled)"
                val page = cur.findDestinationPage(doc)
                val idx = if (page != null) doc.getPages().indexOf(page) else -1
                out.add(Triple(depth, idx, title))
                collectOutlineItems(cur.firstChild, depth + 1, doc, out)
            } catch (e: Exception) { }
            cur = cur.nextSibling
        }
    }

    // ---------- goto ----------

    private fun showGoto() {
        val input = EditText(this).apply { hint = "1 - $pageCount" }
        AlertDialog.Builder(this)
            .setTitle("Go to page")
            .setView(input)
            .setPositiveButton("Go") { _, _ ->
                val n = input.text.toString().toIntOrNull()
                if (n != null && n in 1..pageCount) jumpTo(n - 1)
                else Toast.makeText(this, "Page out of range", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun jumpTo(pageIndex: Int) {
        (recycler.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(pageIndex, 0)
    }

    // ---------- recycler adapter ----------

    private inner class PagesAdapter : RecyclerView.Adapter<PagesAdapter.VH>() {

        inner class VH(val img: ZoomableImageView) : RecyclerView.ViewHolder(img)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_pdf_page, parent, false)
            return VH(v.findViewById(R.id.imgPage))
        }

        override fun getItemCount(): Int = pageCount

        override fun onBindViewHolder(holder: VH, position: Int) {
            val (pw, ph) = pageDims[position]
            var w = targetWidth
            var h = (w.toLong() * ph / pw).toInt()
            if (h > 4096) { h = 4096; w = (h.toLong() * pw / ph).toInt() }
            holder.img.layoutParams = holder.img.layoutParams.apply { height = h }
            holder.img.setColorFilter(if (nightMode) nightFilter else android.graphics.ColorFilter())

            val key = "$position-$w"
            val cached = cache.get(key)
            if (cached != null) {
                holder.img.setImageBitmap(cached)
                return
            }
            holder.img.setImageBitmap(null)
            val stamp = position
            renderExecutor.execute {
                try {
                    val bmp = renderPage(stamp)
                    cache.put(key, bmp)
                    runOnUiThread {
                        if (holder.absoluteAdapterPosition == stamp) {
                            holder.img.setImageBitmap(bmp)
                            holder.img.setColorFilter(if (nightMode) nightFilter else android.graphics.ColorFilter())
                        }
                    }
                } catch (e: Exception) { /* renderer closed */ }
            }
        }
    }

    private val nightFilter = ColorMatrixColorFilter(
        floatArrayOf(
            -1f, 0f, 0f, 0f, 255f,
            0f, -1f, 0f, 0f, 255f,
            0f, 0f, -1f, 0f, 255f,
            0f, 0f, 0f, 1f, 0f
        )
    )

    override fun onPause() {
        super.onPause()
        val lm = recycler.layoutManager as? LinearLayoutManager ?: return
        store.saveLastPage(uri.toString(), lm.findFirstVisibleItemPosition())
    }

    override fun onDestroy() {
        super.onDestroy()
        renderExecutor.shutdownNow()
        runCatching { renderer?.close() }
        runCatching { pfd?.close() }
        runCatching { pdfBoxDoc?.close() }
    }
}
