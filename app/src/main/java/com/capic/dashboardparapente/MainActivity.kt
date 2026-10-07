package com.capic.dashboardparapente

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.util.Base64
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.Toast
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.Locale

class MainActivity : Activity() {
    private lateinit var webView: WebView
    private lateinit var loadingIndicator: ProgressBar
    private lateinit var swipeRefreshLayout: SwipeRefreshLayout
    private var fullscreenVideoView: View? = null
    private var fullscreenVideoCallback: WebChromeClient.CustomViewCallback? = null
    private var fullscreenUiVisibilityBefore: Int? = null
    private var nativeFullscreenActive = false
    private var nativeFullscreenOrientationBefore: Int? = null
    private var nativeFullscreenUiVisibilityBefore: Int? = null
    private var pendingFullscreenLandscape: Boolean? = null
    private var touchInProgress = false
    private var pendingGeolocationCallback: GeolocationPermissions.Callback? = null
    private var pendingGeolocationOrigin: String? = null
    private var pendingFileChooserCallback: ValueCallback<Array<Uri>>? = null
    private var pendingSharedGpx: SharedGpx? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        webView = findViewById(R.id.dashboard_web_view)
        loadingIndicator = findViewById(R.id.loading_indicator)
        swipeRefreshLayout = findViewById(R.id.swipe_refresh_layout)
        swipeRefreshLayout.setOnRefreshListener { webView.reload() }
        swipeRefreshLayout.setOnChildScrollUpCallback { _, _ ->
            webView.canScrollVertically(-1)
        }
        webView.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    touchInProgress = true
                    swipeRefreshLayout.isEnabled = false
                    swipeRefreshLayout.requestDisallowInterceptTouchEvent(true)

                    val x = event.x / webView.scale
                    val y = event.y / webView.scale
                    webView.evaluateJavascript(
                        String.format(Locale.US, NESTED_SCROLL_TARGET_SCRIPT, x, y),
                    ) { result ->
                        if (touchInProgress) {
                            val startedInNestedScroll = result == "\"nested\""
                            swipeRefreshLayout.isEnabled = !startedInNestedScroll
                            swipeRefreshLayout.requestDisallowInterceptTouchEvent(
                                startedInNestedScroll,
                            )
                        }
                    }
                }

                android.view.MotionEvent.ACTION_UP,
                android.view.MotionEvent.ACTION_CANCEL -> {
                    touchInProgress = false
                    swipeRefreshLayout.isEnabled = true
                    swipeRefreshLayout.requestDisallowInterceptTouchEvent(false)
                }
            }
            false
        }
        configureWebView()
        val hasIncomingGpx = handleIncomingGpx(intent)

        if (savedInstanceState == null || hasIncomingGpx) {
            webView.loadUrl(DASHBOARD_URL)
        } else {
            webView.restoreState(savedInstanceState)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (handleIncomingGpx(intent) && isDashboardPageLoaded()) {
            webView.reload()
        }
    }

    @Suppress("SetJavaScriptEnabled")
    private fun configureWebView() {
        with(webView.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            loadWithOverviewMode = true
            useWideViewPort = true
            mediaPlaybackRequiresUserGesture = false
        }

        CookieManager.getInstance().setAcceptCookie(true)
        webView.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        webView.addJavascriptInterface(NativeFullscreenBridge(), NATIVE_FULLSCREEN_BRIDGE_NAME)
        webView.addJavascriptInterface(NativeGpxShareBridge(), NATIVE_GPX_SHARE_BRIDGE_NAME)

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                swipeRefreshLayout.isRefreshing = false
            }

            override fun onReceivedError(
                view: WebView,
                request: WebResourceRequest,
                error: android.webkit.WebResourceError,
            ) {
                if (request.isForMainFrame) {
                    swipeRefreshLayout.isRefreshing = false
                }
            }

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val url = request.url
                return if (url.host == DASHBOARD_HOST && url.scheme == "https") {
                    false
                } else {
                    openExternalUrl(url)
                    true
                }
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                loadingIndicator.progress = newProgress
                loadingIndicator.visibility = if (newProgress == 100) View.GONE else View.VISIBLE
            }

            override fun onShowFileChooser(
                webView: WebView,
                filePathCallback: ValueCallback<Array<Uri>>,
                fileChooserParams: FileChooserParams,
            ): Boolean {
                pendingFileChooserCallback?.onReceiveValue(null)
                pendingFileChooserCallback = filePathCallback
                return try {
                    startActivityForResult(
                        fileChooserParams.createIntent(),
                        FILE_CHOOSER_REQUEST_CODE,
                    )
                    true
                } catch (_: ActivityNotFoundException) {
                    pendingFileChooserCallback = null
                    filePathCallback.onReceiveValue(null)
                    false
                }
            }

            override fun onGeolocationPermissionsShowPrompt(
                origin: String,
                callback: GeolocationPermissions.Callback,
            ) {
                if (!isDashboardOrigin(origin)) {
                    callback.invoke(origin, false, false)
                    return
                }

                if (hasLocationPermission()) {
                    callback.invoke(origin, true, false)
                    return
                }

                pendingGeolocationOrigin?.let { previousOrigin ->
                    pendingGeolocationCallback?.invoke(previousOrigin, false, false)
                }
                pendingGeolocationCallback = callback
                pendingGeolocationOrigin = origin
                requestPermissions(
                    arrayOf(
                        android.Manifest.permission.ACCESS_COARSE_LOCATION,
                        android.Manifest.permission.ACCESS_FINE_LOCATION,
                    ),
                    LOCATION_PERMISSION_REQUEST_CODE,
                )
            }

            override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                if (fullscreenVideoView != null) {
                    callback.onCustomViewHidden()
                    return
                }

                fullscreenVideoView = view
                fullscreenVideoCallback = callback
                fullscreenUiVisibilityBefore = window.decorView.systemUiVisibility
                window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
                window.decorView.systemUiVisibility =
                    View.SYSTEM_UI_FLAG_FULLSCREEN or
                        View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                        View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                        View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                        View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                        View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                window.addContentView(
                    view,
                    FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        Gravity.CENTER,
                    ),
                )
                loadingIndicator.visibility = View.GONE
            }

            override fun onHideCustomView() {
                hideFullscreenVideo()
            }
        }
    }

    private fun handleIncomingGpx(intent: Intent?): Boolean {
        if (
            intent == null ||
            (intent.action != Intent.ACTION_SEND && intent.action != Intent.ACTION_VIEW)
        ) {
            return false
        }

        val uri = if (intent.action == Intent.ACTION_SEND) {
            @Suppress("DEPRECATION")
            (intent.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri)
                ?: intent.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.uri
        } else {
            intent.data
        } ?: run {
            Toast.makeText(this, R.string.gpx_share_read_error, Toast.LENGTH_LONG).show()
            return false
        }

        return try {
            val filename = queryDisplayName(uri) ?: uri.lastPathSegment ?: "trace.gpx"
            if (!filename.endsWith(".gpx", ignoreCase = true)) {
                Toast.makeText(this, R.string.gpx_share_invalid_file, Toast.LENGTH_LONG).show()
                return false
            }

            val bytes = contentResolver.openInputStream(uri)?.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8 * 1024)
                var total = 0
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    require(total <= MAX_SHARED_GPX_BYTES) { "GPX file is too large" }
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            } ?: throw IllegalArgumentException("Cannot read shared file")

            if (bytes.isEmpty()) throw IllegalArgumentException("Empty GPX file")
            synchronized(this) {
                pendingSharedGpx = SharedGpx(
                    filename = filename.substringAfterLast('/'),
                    base64 = Base64.encodeToString(bytes, Base64.NO_WRAP),
                )
            }
            Toast.makeText(this, R.string.gpx_share_received, Toast.LENGTH_SHORT).show()
            true
        } catch (_: Exception) {
            Toast.makeText(this, R.string.gpx_share_read_error, Toast.LENGTH_LONG).show()
            false
        }
    }

    private fun queryDisplayName(uri: Uri): String? = try {
        contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
    } catch (_: Exception) {
        null
    }

    @Deprecated("Deprecated in Java")
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != LOCATION_PERMISSION_REQUEST_CODE) return

        val origin = pendingGeolocationOrigin
        val callback = pendingGeolocationCallback
        pendingGeolocationOrigin = null
        pendingGeolocationCallback = null
        if (origin != null && callback != null) {
            callback.invoke(origin, hasLocationPermission(), false)
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != FILE_CHOOSER_REQUEST_CODE) return

        val callback = pendingFileChooserCallback ?: return
        pendingFileChooserCallback = null
        callback.onReceiveValue(
            if (resultCode == RESULT_OK) {
                WebChromeClient.FileChooserParams.parseResult(resultCode, data)
            } else {
                null
            },
        )
    }

    private fun hasLocationPermission(): Boolean =
        checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    private fun isDashboardOrigin(origin: String): Boolean = try {
        val uri = Uri.parse(origin)
        uri.scheme == "https" && uri.host == DASHBOARD_HOST
    } catch (_: Exception) {
        false
    }

    private fun hideFullscreenVideo(notifyWebContent: Boolean = false) {
        val view = fullscreenVideoView ?: return
        val callback = fullscreenVideoCallback
        (view.parent as? ViewGroup)?.removeView(view)
        fullscreenVideoView = null
        fullscreenVideoCallback = null
        window.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        fullscreenUiVisibilityBefore?.let { previousVisibility ->
            window.decorView.systemUiVisibility = previousVisibility
        }
        fullscreenUiVisibilityBefore = null
        if (notifyWebContent) {
            callback?.onCustomViewHidden()
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        webView.invalidate()
        webView.requestLayout()
        window.decorView.requestLayout()
        window.decorView.postOnAnimation {
            notifyFullscreenOrientationWhenLaidOut()
        }
    }

    private fun enterNativeFullscreen() {
        if (nativeFullscreenActive) return
        nativeFullscreenActive = true
        nativeFullscreenOrientationBefore = requestedOrientation
        nativeFullscreenUiVisibilityBefore = window.decorView.systemUiVisibility
        window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        pendingFullscreenLandscape = true
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        webView.postOnAnimation { notifyFullscreenOrientationWhenLaidOut() }
    }

    private fun exitNativeFullscreen() {
        if (!nativeFullscreenActive) return
        nativeFullscreenActive = false
        pendingFullscreenLandscape = false
        requestedOrientation = nativeFullscreenOrientationBefore
            ?: ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        nativeFullscreenOrientationBefore = null
        window.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        nativeFullscreenUiVisibilityBefore?.let { visibility ->
            window.decorView.systemUiVisibility = visibility
        }
        nativeFullscreenUiVisibilityBefore = null
        webView.postOnAnimation { notifyFullscreenOrientationWhenLaidOut() }
    }

    private fun notifyFullscreenOrientationWhenLaidOut() {
        val targetLandscape = pendingFullscreenLandscape ?: return
        val actualLandscape = resources.configuration.orientation ==
            Configuration.ORIENTATION_LANDSCAPE
        if (actualLandscape == targetLandscape) {
            webView.postOnAnimation {
                if (pendingFullscreenLandscape != targetLandscape) return@postOnAnimation
                val stillLandscape = resources.configuration.orientation ==
                    Configuration.ORIENTATION_LANDSCAPE
                if (stillLandscape != targetLandscape) return@postOnAnimation

                pendingFullscreenLandscape = null
                val isLandscape = targetLandscape
                webView.evaluateJavascript(
                    "window.dispatchEvent(new CustomEvent('nativefullscreenorientationchange', { detail: { landscape: $isLandscape } }))",
                    null,
                )
            }
        }
    }

    private fun isDashboardPageLoaded(): Boolean =
        Uri.parse(webView.url).let { it.scheme == "https" && it.host == DASHBOARD_HOST }

    inner class NativeFullscreenBridge {
        @JavascriptInterface
        fun supportsOrientationReady(): Boolean = true

        @JavascriptInterface
        fun enter() {
            runOnUiThread {
                if (isDashboardPageLoaded()) enterNativeFullscreen()
            }
        }

        @JavascriptInterface
        fun exit() {
            runOnUiThread {
                if (isDashboardPageLoaded()) exitNativeFullscreen()
            }
        }
    }

    inner class NativeGpxShareBridge {
        @JavascriptInterface
        fun consumeSharedGpx(): String? {
            if (!isDashboardPageLoaded()) return null
            return synchronized(this@MainActivity) {
                val shared = pendingSharedGpx ?: return@synchronized null
                pendingSharedGpx = null
                JSONObject()
                    .put("filename", shared.filename)
                    .put("base64", shared.base64)
                    .toString()
            }
        }
    }

    private data class SharedGpx(val filename: String, val base64: String)

    private fun openExternalUrl(url: Uri) {
        startActivity(Intent(Intent.ACTION_VIEW, url))
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (nativeFullscreenActive) {
            webView.evaluateJavascript(
                "window.dispatchEvent(new Event('nativefullscreenback'))",
                null,
            )
        } else if (fullscreenVideoView != null) {
            hideFullscreenVideo(notifyWebContent = true)
        } else if (webView.canGoBack()) {
            webView.goBack()
        } else {
            super.onBackPressed()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        webView.saveState(outState)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        exitNativeFullscreen()
        hideFullscreenVideo()
        webView.destroy()
        super.onDestroy()
    }

    private companion object {
        const val DASHBOARD_URL = "https://parapente.capic.ignorelist.com"
        const val DASHBOARD_HOST = "parapente.capic.ignorelist.com"
        const val NATIVE_FULLSCREEN_BRIDGE_NAME = "NativeFullscreen"
        const val NATIVE_GPX_SHARE_BRIDGE_NAME = "NativeGpxShare"
        const val MAX_SHARED_GPX_BYTES = 20 * 1024 * 1024
        const val LOCATION_PERMISSION_REQUEST_CODE = 1001
        const val FILE_CHOOSER_REQUEST_CODE = 1002
        const val NESTED_SCROLL_TARGET_SCRIPT = """
            (function(x, y) {
                let element = document.elementFromPoint(x, y);
                while (element && element !== document.body && element !== document.documentElement) {
                    if (/(iframe|video|audio)/.test(element.tagName.toLowerCase())) return "nested";
                    const style = window.getComputedStyle(element);
                    const scrollable = element.scrollHeight > element.clientHeight + 1 &&
                        /(auto|scroll|overlay)/.test(style.overflowY);
                    if (scrollable) return "nested";
                    element = element.parentElement;
                }
                return "root";
            })(%f, %f)
        """
    }
}
