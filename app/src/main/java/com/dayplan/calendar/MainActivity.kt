package com.dayplan.calendar

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.webkit.MimeTypeMap
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewAssetLoader
import java.io.ByteArrayInputStream

class MainActivity : Activity() {
    private lateinit var web: WebView
    private var seenOutside = 0L

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        web = WebView(this)
        setContentView(web)

        val loader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", PageHandler())
            .build()
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) { openPendingDate() }

            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                loader.shouldInterceptRequest(request.url)

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                if (request.url.host == HOST) return false
                runCatching { startActivity(Intent(Intent.ACTION_VIEW, request.url)) }
                return true
            }
        }
        web.addJavascriptInterface(Bridge(this), "Android")
        takeDate(intent)
        seenOutside = Store.outsideVersion(this)
        web.loadUrl("https://$HOST/assets/index.html")

        if (!CalendarRepo.hasPermission(this)) {
            requestPermissions(arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR), REQ_CAL)
        }
    }

    private var pendingDate: String? = null

    private fun takeDate(i: Intent?) { pendingDate = i?.getStringExtra(CalendarWidget.EXTRA_DATE) }

    private fun openPendingDate() {
        val d = pendingDate ?: return
        pendingDate = null
        if (Regex("^\\d{4}-\\d{2}-\\d{2}$").matches(d)) web.evaluateJavascript("window.__openDate && window.__openDate('$d')", null)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        takeDate(intent)
        openPendingDate()
    }

    override fun onResume() {
        super.onResume()
        // Goals checked off on the widget: show them.
        val v = Store.outsideVersion(this)
        if (v != seenOutside) { seenOutside = v; web.reload() }
    }

    override fun onPause() {
        super.onPause()
        CalendarWidget.refresh(this)
    }

    override fun onStop() {
        super.onStop()
        // Leaving the app locks the 일기 tab.
        web.evaluateJavascript("window.Diary && window.Diary.lock()", null)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_CAL && grantResults.any { it == PackageManager.PERMISSION_GRANTED }) {
            web.reload()
            CalendarWidget.refresh(this)
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        web.evaluateJavascript("!!(window.Diary && window.Diary.back())") { handled ->
            if (handled == "true") return@evaluateJavascript
            if (web.canGoBack()) web.goBack() else finish()
        }
    }

    /** Serves the bundled page, wrapped in a document with the bridge loaded first. */
    private inner class PageHandler : WebViewAssetLoader.PathHandler {
        override fun handle(path: String): WebResourceResponse? {
            return try {
                if (path == "index.html") {
                    val body = assets.open("index.html").bufferedReader().use { it.readText() }
                    val html = "<!doctype html><html lang=\"ko\"><head><meta charset=\"utf-8\">" +
                        "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1, viewport-fit=cover\">" +
                        "<script src=\"bridge.js\"></script>\n" + body + "\n</html>"
                    WebResourceResponse("text/html", "utf-8", ByteArrayInputStream(html.toByteArray()))
                } else {
                    val ext = MimeTypeMap.getFileExtensionFromUrl(path)
                    val mime = if (ext == "js") "text/javascript"
                    else MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
                    WebResourceResponse(mime, "utf-8", assets.open(Uri.decode(path)))
                }
            } catch (e: Exception) { null }
        }
    }

    companion object {
        const val REQ_CAL = 7
        const val HOST = "appassets.androidplatform.net"
    }
}
