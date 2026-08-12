package com.shreeyog.shreeenglishclasses

import android.Manifest
import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import android.view.KeyEvent
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.io.File
import java.io.FileOutputStream

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private val siteUrl = "https://dadu2122.github.io/Shree-English-Classes/"
    private val MIC_PERMISSION_REQUEST_CODE = 101

    // ---------- File chooser plumbing ----------
    // Plain Android WebView does NOT open the OS file picker when a page taps an
    // <input type="file">, unless WebChromeClient.onShowFileChooser is implemented
    // and actually launches the picker intent. Without this, "Choose file" buttons
    // (Add Mini Book / PDF, Cover Photo, etc.) silently do nothing — the exact bug
    // reported. This launcher + callback pair wires that up properly.
    private var fileChooserCallback: ValueCallback<Array<Uri>>? = null
    private val fileChooserLauncher: ActivityResultLauncher<Intent> =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val results = WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data)
            fileChooserCallback?.onReceiveValue(results)
            fileChooserCallback = null
        }

    // Exposed to the page's JavaScript as `window.AndroidApp`.
    inner class WebAppInterface {

        // Live Class needs the microphone — that part is opened in the device's real
        // Chrome browser instead of this app's embedded WebView, since Chrome's mic
        // access is more reliable across devices.
        @JavascriptInterface
        fun openLiveInBrowser(url: String) {
            runOnUiThread {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                intent.setPackage("com.android.chrome")
                try {
                    startActivity(intent)
                } catch (e: Exception) {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                }
            }
        }

        // Saves a base64-encoded file (PDFs, etc.) straight to the phone's Downloads
        // folder. Needed because Android WebView does NOT treat blob: URL downloads
        // (the normal web way of doing "Save File") as real downloads inside an app's
        // own WebView — clicking a <a download> link on a blob URL silently does
        // nothing here, even though the exact same code works fine in Chrome. This
        // bridge is the reliable native-side workaround for that.
        @JavascriptInterface
        fun saveBase64File(base64Data: String, fileName: String, mimeType: String) {
            runOnUiThread {
                try {
                    val bytes = Base64.decode(base64Data, Base64.DEFAULT)
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                        // Android 10+ : save via MediaStore (Downloads collection),
                        // no storage permission needed.
                        val resolver = contentResolver
                        val values = ContentValues().apply {
                            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                        }
                        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                        if (uri != null) {
                            resolver.openOutputStream(uri)?.use { out -> out.write(bytes) }
                            Toast.makeText(this@MainActivity, "Downloaded: $fileName", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(this@MainActivity, "Download failed — try again.", Toast.LENGTH_SHORT).show()
                        }
                    } else {
                        // Older Android — write directly to the public Downloads directory.
                        val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                        if (!downloadsDir.exists()) downloadsDir.mkdirs()
                        val file = File(downloadsDir, fileName)
                        FileOutputStream(file).use { out -> out.write(bytes) }
                        Toast.makeText(this@MainActivity, "Downloaded: $fileName", Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) {
                    Toast.makeText(this@MainActivity, "Download failed: ${e.message}", Toast.LENGTH_SHORT).show()
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
        val host = url.host ?: ""
        val isOwnSite = host.equals("dadu2122.github.io", ignoreCase = true)
        if (!isOwnSite) {
            // Any external site (Teachmint, Google Meet, YouTube live, etc.) opens in the
            // phone's real Chrome instead of this app's embedded WebView — WebView blocks
            // or mishandles microphone access on many third-party sites, while Chrome
            // handles it reliably. Same fix already used for openLiveInBrowser(), just
            // applied to every external link, not only the built-in Live Class button.
            return try {
                val chromeIntent = Intent(Intent.ACTION_VIEW, url)
                chromeIntent.setPackage("com.android.chrome")
                try {
                    startActivity(chromeIntent)
                } catch (e: ActivityNotFoundException) {
                    startActivity(Intent(Intent.ACTION_VIEW, url))
                }
                true
            } catch (e: Exception) {
                false
            }
        }
        return false // your own site — keep loading inside the app's WebView as before
    }
    return try {
        startActivity(Intent(Intent.ACTION_VIEW, url))
        true
    } catch (e: ActivityNotFoundException) {
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

            // This is the piece that was missing: without it, tapping any
            // <input type="file"> in the page (Book Title's "Choose PDF",
            // "Cover Photo", etc.) does absolutely nothing — Android WebView
            // needs this callback implemented to actually open a file picker.
            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                // If a previous chooser is somehow still pending, cancel it cleanly
                // instead of leaking the callback.
                fileChooserCallback?.onReceiveValue(null)
                fileChooserCallback = filePathCallback

                val intent = fileChooserParams?.createIntent()
                return try {
                    fileChooserLauncher.launch(intent)
                    true
                } catch (e: ActivityNotFoundException) {
                    fileChooserCallback = null
                    Toast.makeText(this@MainActivity, "No file picker app found on this device.", Toast.LENGTH_SHORT).show()
                    false
                } catch (e: Exception) {
                    fileChooserCallback = null
                    Toast.makeText(this@MainActivity, "Could not open file picker: ${e.message}", Toast.LENGTH_SHORT).show()
                    false
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

