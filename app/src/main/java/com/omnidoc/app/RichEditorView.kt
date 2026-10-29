package com.omnidoc.app

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.util.AttributeSet
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONTokener

/**
 * Lightweight WYSIWYG rich-text editor built on a WebView contenteditable body.
 * Communicates with assets/editor.html through execCommand and evaluateJavascript.
 */
class RichEditorView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : WebView(context, attrs) {

    interface Listener {
        fun onSelectionStateChanged(bold: Boolean, italic: Boolean, underline: Boolean, strike: Boolean)
        fun onContentChanged()
    }

    var listener: Listener? = null
    private var pageReady = false
    private var pendingHtml: String? = null

    @SuppressLint("SetJavaScriptEnabled")
    init {
        settings.javaScriptEnabled = true
        settings.allowFileAccess = true
        settings.loadWithOverviewMode = true
        isVerticalScrollBarEnabled = true
        webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                pageReady = true
                pendingHtml?.let { setHtml(it) }
                pendingHtml = null
            }
        }
        webChromeClient = WebChromeClient()
        addJavascriptInterface(Bridge(), "AndroidNative")
        loadUrl("file:///android_asset/editor.html")
    }

    private inner class Bridge {
        @JavascriptInterface
        fun state(json: String) {
            try {
                val o = JSONTokener(json).nextValue() as org.json.JSONObject
                post {
                    listener?.onSelectionStateChanged(
                        o.optBoolean("b"), o.optBoolean("i"),
                        o.optBoolean("u"), o.optBoolean("s")
                    )
                }
            } catch (e: Exception) { }
        }

        @JavascriptInterface
        fun changed(length: Int) {
            post { listener?.onContentChanged() }
        }
    }

    private fun cmd(name: String, value: String? = null) {
        val v = value?.let { "'" + it.replace("'", "\\'") + "'" } ?: "null"
        evaluateJavascript("cmd('$name', $v);", null)
    }

    fun setHtml(html: String) {
        if (!pageReady) {
            pendingHtml = html
            return
        }
        val escaped = html
            .replace("\\", "\\\\")
            .replace("'", "\\'")
            .replace("\n", "\\n")
            .replace("\r", "")
        evaluateJavascript("setHtml('$escaped');", null)
    }

    suspend fun getHtml(): String {
        val raw = kotlinx.coroutines.suspendCancellableCoroutine<String?> { cont ->
            evaluateJavascript("getHtml();") { result -> cont.resume(result) { } }
        }
        return (JSONTokener(raw ?: "\"\"").nextValue() as? String) ?: ""
    }

    fun bold() = cmd("bold")
    fun italic() = cmd("italic")
    fun underline() = cmd("underline")
    fun strike() = cmd("strikeThrough")
    fun undo() = cmd("undo")
    fun redo() = cmd("redo")
    fun bullets() = cmd("insertUnorderedList")
    fun numbers() = cmd("insertOrderedList")
    fun insertImage(dataUri: String) = cmd("insertImage", dataUri)
    fun insertTable() = evaluateJavascript("insertTable();", null)
    fun setHeading(level: Int) =
        cmd("formatBlock", if (level in 1..6) "h$level" else "p")
    fun setFontFamily(family: String) = cmd("fontName", family)
    fun setFontSize(cssSize: String) = cmd("fontSize", cssSize)
    fun setTextColor(hex: String) = cmd("foreColor", hex)
    fun setHighlight(hex: String) = cmd("hiliteColor", hex)
    fun alignLeft() = cmd("justifyLeft")
    fun alignCenter() = cmd("justifyCenter")
    fun alignRight() = cmd("justifyRight")
    fun alignJustify() = cmd("justifyFull")
}
