package com.shreeyog.shreeenglishclasses

import android.Manifest
import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.view.KeyEvent
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private val siteUrl = "https://dadu2122.github.io/Shree-English-Classes/"
    private val MIC_PERMISSION_REQUEST_CODE = 101

    // Exposed to the page's JavaScript as `window.AndroidApp`.
    // Used only for the Live Class feature, which needs the microphone —
    // that part is opened in the device's real Chrome browser instead of
    // this app's embedded WebView, since Chrome's mic access is more
    // reliable across devices.
    inner class WebAppInterface {
        @JavascriptInterface
        fun openLiveInBrowser(url: String) {
            runOnUiThread {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                intent.setPackage("com.android.chrome")
                try {
                    startActivity(intent)
                } catch (e: Exception) {
                    // Chrome not found/available — fall back to whatever
                    // browser the device has set as default.
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                }
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.RECORD_AUDIO),
                MIC_PERMISSION_REQUEST_CODE
            )
        }

        webView = findViewById(R.id.webview)
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.databaseEnabled = true
        webView.settings.loadWithOverviewMode = true
        webView.settings.useWideViewPort = true
        webView.settings.mediaPlaybackRequiresUserGesture = false
        // Always fetch the live site fresh over the network instead of serving a
        // possibly-outdated cached copy — this is what was causing students to see
        // stale versions of the app until they cleared cache / reinstalled.
        webView.settings.cacheMode = WebSettings.LOAD_NO_CACHE

        webView.addJavascriptInterface(WebAppInterface(), "AndroidApp")

        webView.webViewClient = object : WebViewClient() {
            // WebView can only render http/https pages itself. Links like
            // whatsapp://, tel:, mailto:, intent:// etc. need to be handed
            // off to the matching app on the phone instead — otherwise the
            // WebView shows a "Web page not available / ERR_UNKNOWN_URL_SCHEME"
            // error, which is what was happening with the WhatsApp button.
            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest
            ): Boolean {
                val url = request.url
                val scheme = url.scheme ?: ""
                if (scheme == "http" || scheme == "https") {
                    return false // let the WebView load it normally
                }
                return try {
                    startActivity(Intent(Intent.ACTION_VIEW, url))
                    true
                } catch (e: ActivityNotFoundException) {
                    // No app installed to handle this link (e.g. WhatsApp not
                    // installed) — nothing sensible to do but avoid crashing.
                    true
                }
            }
        }
        webView.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest) {
                runOnUiThread {
                    val resources = request.resources
                    val audioNeeded = resources.any {
                        it == PermissionRequest.RESOURCE_AUDIO_CAPTURE
                    }
                    if (audioNeeded && ContextCompat.checkSelfPermission(
                            this@MainActivity, Manifest.permission.RECORD_AUDIO
                        ) == PackageManager.PERMISSION_GRANTED
                    ) {
                        request.grant(resources)
                    } else {
                        request.deny()
                    }
                }
            }
        }

        // Always (re)load the live site on every launch — including when Android has
        // killed the app in the background and the user reopens it, which used to be
        // treated as a "restore" (savedInstanceState != null) and skip loading fresh
        // content, leaving whatever stale page was left in memory.
        webView.loadUrl(siteUrl)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == MIC_PERMISSION_REQUEST_CODE &&
            grantResults.isNotEmpty() &&
            grantResults[0] == PackageManager.PERMISSION_GRANTED
        ) {
            webView.reload()
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK && webView.canGoBack()) {
            webView.goBack()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }
}
