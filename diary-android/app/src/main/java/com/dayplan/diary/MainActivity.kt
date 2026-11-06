package com.dayplan.diary

import android.annotation.SuppressLint
import android.app.Activity
import android.net.Uri
import android.os.Bundle
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.MimeTypeMap
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewAssetLoader
import java.io.File

class MainActivity : Activity() {
    private lateinit var web: WebView

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Hide the diary from the recent-apps preview and screenshots.
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)

        web = WebView(this)
        setContentView(web)

        val loader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", AssetHandler())
            .build()
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.allowFileAccess = false
        web.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                loader.shouldInterceptRequest(request.url)

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                request.url.host != HOST
        }
        web.addJavascriptInterface(Storage(File(filesDir, "diary.json")), "Android")
        web.loadUrl("https://$HOST/assets/index.html")
    }

    override fun onStop() {
        super.onStop()
        // Leaving the app (home button, screen off, switching apps) locks the diary.
        web.evaluateJavascript("window.__lock && window.__lock()", null)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        web.evaluateJavascript("window.__back ? window.__back() : false") { handled ->
            if (handled != "true") finish()
        }
    }

    /** Entries and the password hash, kept in the app's private storage. */
    private class Storage(private val file: File) {
        @JavascriptInterface
        fun load(): String = if (file.exists()) file.readText() else ""

        @JavascriptInterface
        fun save(json: String) {
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(json)
            if (!tmp.renameTo(file)) { file.writeText(json); tmp.delete() }
        }
    }

    private inner class AssetHandler : WebViewAssetLoader.PathHandler {
        override fun handle(path: String): WebResourceResponse? = try {
            val ext = MimeTypeMap.getFileExtensionFromUrl(path)
            val mime = when (ext) {
                "html" -> "text/html"
                "js" -> "text/javascript"
                else -> MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
            }
            WebResourceResponse(mime, "utf-8", assets.open(Uri.decode(path)))
        } catch (e: Exception) { null }
    }

    companion object {
        const val HOST = "appassets.androidplatform.net"
    }
}
