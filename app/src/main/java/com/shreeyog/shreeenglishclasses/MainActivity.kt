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
// ---------- Native Agora (Phase 1: audio only) additions ----------
import io.agora.rtc2.RtcEngine
import io.agora.rtc2.RtcEngineConfig
import io.agora.rtc2.IRtcEngineEventHandler
import io.agora.rtc2.ChannelMediaOptions
import io.agora.rtc2.Constants
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private val siteUrl = "https://dadu2122.github.io/Shree-English-Classes/"
    private val MIC_PERMISSION_REQUEST_CODE = 101

    // ---------- Native Agora (Phase 1: audio only) ----------
    // Same App ID as the JS side's AGORA_APP_ID (index.html, ~line 7527) — keep these two in sync
    // if the Agora project is ever rotated.
    private var agoraEngine: RtcEngine? = null
    private val AGORA_APP_ID = "5b0232817d3b4c33a96d515a476e6a5f"
    private val AGORA_TOKEN_SERVER = "https://shreeyog-agora-token-server.vercel.app/api/generate-token"
    private val agoraScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private val agoraEventHandler = object : IRtcEngineEventHandler() {
        override fun onJoinChannelSuccess(channel: String?, uid: Int, elapsed: Int) {
            runOnUiThread {
                webView.evaluateJavascript(
                    "window.onNativeAgoraJoined && window.onNativeAgoraJoined($uid)", null
                )
            }
        }
        override fun onUserJoined(uid: Int, elapsed: Int) {
            runOnUiThread {
                webView.evaluateJavascript(
                    "window.onNativeAgoraUserJoined && window.onNativeAgoraUserJoined($uid)", null
                )
            }
        }
        override fun onUserOffline(uid: Int, reason: Int) {
            runOnUiThread {
                webView.evaluateJavascript(
                    "window.onNativeAgoraUserLeft && window.onNativeAgoraUserLeft($uid)", null
                )
            }
        }
        override fun onError(err: Int) {
            runOnUiThread {
                webView.evaluateJavascript(
                    "window.onNativeAgoraError && window.onNativeAgoraError($err)", null
                )
            }
        }
    }

    private fun ensureAgoraEngine(): RtcEngine {
        if (agoraEngine == null) {
            val config = RtcEngineConfig()
            config.mContext = applicationContext
            config.mAppId = AGORA_APP_ID
            config.mEventHandler = agoraEventHandler
            agoraEngine = RtcEngine.create(config)
            agoraEngine?.enableAudio()
            agoraEngine?.disableVideo() // Phase 1 = audio only; video stays on the JS/Web SDK side for now
        }
        return agoraEngine!!
    }

    private fun hasMicPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    // Talks to the same Vercel token server the JS side already uses (AGORA_TOKEN_SERVER in index.html).
    private suspend fun fetchAgoraToken(channel: String, uid: Int): String? = withContext(Dispatchers.IO) {
        try {
            val url = URL("$AGORA_TOKEN_SERVER?channel=$channel&uid=$uid&role=host")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = 8000
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val response = stream.bufferedReader().readText()
            val json = JSONObject(response)
            if (json.has("token")) json.getString("token") else null
        } catch (e: Exception) {
            null
        }
    }

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

        // Mic permission bridge: called when JS detects a PERMISSION_DENIED error.
        // Checks if permission is granted; if not, requests it.
        // If permanently denied, opens app Settings so user can manually enable mic.
        @JavascriptInterface
        fun recheckMicPermission() {
            runOnUiThread {
                val hasMicPermission = ContextCompat.checkSelfPermission(
                    this@MainActivity,
                    Manifest.permission.RECORD_AUDIO
                ) == PackageManager.PERMISSION_GRANTED

                if (hasMicPermission) {
                    // Permission already granted — just reload the page so mic retry works
                    webView.reload()
                } else {
                    // Check if permission was permanently denied (user tapped "Don't allow" before)
                    val isPermanentlyDenied = !ActivityCompat.shouldShowRequestPermissionRationale(
                        this@MainActivity,
                        Manifest.permission.RECORD_AUDIO
                    )

                    if (isPermanentlyDenied) {
                        // Permanently denied — show a toast and open app Settings
                        Toast.makeText(
                            this@MainActivity,
                            "Mic access block hai — Settings me allow karo",
                            Toast.LENGTH_LONG
                        ).show()
                        openAppSettings()
                    } else {
                        // Not yet denied, or can still ask — request permission
                        ActivityCompat.requestPermissions(
                            this@MainActivity,
                            arrayOf(Manifest.permission.RECORD_AUDIO),
                            MIC_PERMISSION_REQUEST_CODE
                        )
                    }
                }
            }
        }

        // Helper: open the app's own Settings page
        private fun openAppSettings() {
            val intent = Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", packageName, null)
            }
            startActivity(intent)
        }

        // ---------- Native Agora bridge (Phase 1: audio only) ----------
        // Called from index.html's liveJoinChannel() instead of AgoraRTC.createMicrophoneAudioTrack()
        // when running inside this app (IS_NATIVE_ANDROID_AGORA flag in the JS).
        @JavascriptInterface
        fun joinLiveClassAudio(channel: String, uid: Int) {
            if (!hasMicPermission()) {
                // Reuses the same permission-request path as recheckMicPermission() above.
                // onRequestPermissionsResult() reloads the page on grant, same as the existing
                // flow — user just taps the mic/join button again once the page reloads.
                runOnUiThread {
                    ActivityCompat.requestPermissions(
                        this@MainActivity,
                        arrayOf(Manifest.permission.RECORD_AUDIO),
                        MIC_PERMISSION_REQUEST_CODE
                    )
                }
                return
            }
            agoraScope.launch {
                val token = fetchAgoraToken(channel, uid)
                if (token == null) {
                    webView.evaluateJavascript(
                        "window.onNativeAgoraError && window.onNativeAgoraError('TOKEN_FETCH_FAILED')", null
                    )
                    return@launch
                }
                val engine = ensureAgoraEngine()
                val options = ChannelMediaOptions()
                options.channelProfile = Constants.CHANNEL_PROFILE_LIVE_BROADCASTING
                options.clientRoleType = Constants.CLIENT_ROLE_BROADCASTER
                options.publishMicrophoneTrack = true
                options.autoSubscribeAudio = true
                engine.joinChannel(token, channel, uid, options)
            }
        }

        @JavascriptInterface
        fun setMicMuted(muted: Boolean) {
            runOnUiThread { agoraEngine?.muteLocalAudioStream(muted) }
        }

        @JavascriptInterface
        fun leaveLiveClassAudio() {
            runOnUiThread { agoraEngine?.leaveChannel() }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

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
        webView.settings.cacheMode = WebSettings.LOAD_DEFAULT

        // Razorpay's checkout.js hides the UPI payment method when it detects the
        // standard Android WebView "wv" marker in the User-Agent (it assumes such
        // WebViews can't reliably launch UPI intent apps like GPay/PhonePe, so it
        // only offers Card/NetBanking/Wallet). Stripping that marker makes Razorpay
        // treat this WebView like a normal mobile browser, so UPI shows up in the
        // checkout list — same as it already does when the site is opened in Chrome.
        // Full fix: Android WebView's User-Agent differs from real Chrome in TWO ways —
        // it adds a "; wv)" marker AND an extra "Version/x.x" token before "Chrome/".
        // Removing only "wv" wasn't enough; Razorpay was still detecting the "Version/x.x"
        // token and hiding UPI. Stripping both makes the UA identical to real Chrome's,
        // so Razorpay treats this WebView as a normal mobile browser and shows UPI.
        val defaultUA = webView.settings.userAgentString
        val cleanedUA = defaultUA
            .replace("; wv", "")
            .replace(Regex("Version/[0-9.]+\\s+"), "")
        webView.settings.userAgentString = cleanedUA

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

                // Razorpay's UPI payment flow generates special "intent://" links
                // (Android's Intent-URI format), not plain "upi://" links. Uri.parse()
                // + a plain ACTION_VIEW cannot correctly launch these — Intent.parseUri()
                // with URI_INTENT_SCHEME is required to properly read the target
                // package/action/fallback baked into the string. Without this, tapping
                // GPay/PhonePe/Paytm inside Razorpay checkout silently did nothing (the
                // ActivityNotFoundException from the generic fallback below was swallowed).
                if (scheme == "intent") {
                    return try {
                        val intent = Intent.parseUri(url.toString(), Intent.URI_INTENT_SCHEME)
                        startActivity(intent)
                        true
                    } catch (e: Exception) {
                        // Fallback: if the specific UPI app isn't installed, Razorpay embeds
                        // a "browser_fallback_url" (usually the app's Play Store page) inside
                        // the intent string — open that instead of doing nothing.
                        try {
                            val fallbackUrl = Regex("S\\.browser_fallback_url=([^;]+)")
                                .find(url.toString())?.groupValues?.get(1)
                            if (fallbackUrl != null) {
                                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(Uri.decode(fallbackUrl))))
                            }
                            true
                        } catch (e2: Exception) {
                            true
                        }
                    }
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
                    val audioRequested = resources.any { it == PermissionRequest.RESOURCE_AUDIO_CAPTURE }
                    val videoRequested = resources.any { it == PermissionRequest.RESOURCE_VIDEO_CAPTURE }

                    val audioOk = !audioRequested || ContextCompat.checkSelfPermission(
                        this@MainActivity, Manifest.permission.RECORD_AUDIO
                    ) == PackageManager.PERMISSION_GRANTED
                    val videoOk = !videoRequested || ContextCompat.checkSelfPermission(
                        this@MainActivity, Manifest.permission.CAMERA
                    ) == PackageManager.PERMISSION_GRANTED

                    if ((audioRequested || videoRequested) && audioOk && videoOk) {
                        // Grant exactly what the page asked for (mic-only, camera-only, or both)
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

        // Kick off the network fetch for the live site FIRST, before anything else
        // that isn't strictly required for it (like the permission dialog below) —
        // this is what actually makes the app feel like it opens instantly on touch,
        // since the biggest chunk of "load time" is this network request itself.
        webView.loadUrl(siteUrl)

        // Ask for mic/camera permission after kicking off the page load, not before —
        // the dialog no longer delays the network fetch from starting.
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA),
                MIC_PERMISSION_REQUEST_CODE
            )
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == MIC_PERMISSION_REQUEST_CODE &&
            grantResults.isNotEmpty() &&
            grantResults.any { it == PackageManager.PERMISSION_GRANTED }
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

    override fun onDestroy() {
        agoraEngine?.leaveChannel()
        RtcEngine.destroy()
        agoraEngine = null
        agoraScope.cancel()
        super.onDestroy()
    }
}
