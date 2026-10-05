package com.capic.dashboardparapente

import android.app.Activity
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import java.util.Locale

class MainActivity : Activity() {
    private lateinit var webView: WebView
    private lateinit var loadingIndicator: ProgressBar
    private lateinit var swipeRefreshLayout: SwipeRefreshLayout
    private var fullscreenVideoView: View? = null
    private var fullscreenVideoCallback: WebChromeClient.CustomViewCallback? = null
    private var orientationBeforeFullscreen: Int? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var pendingOrientationRestore: Runnable? = null
    private var pendingFullscreenOrientation: Runnable? = null
    private var fullscreenTraceView: TextView? = null
    private val fullscreenTraceLines = mutableListOf<String>()
    private val hideFullscreenTrace = Runnable {
        fullscreenTraceView?.visibility = View.GONE
    }
    private var touchInProgress = false
    private var pendingGeolocationCallback: GeolocationPermissions.Callback? = null
    private var pendingGeolocationOrigin: String? = null

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

        if (savedInstanceState == null) {
            webView.loadUrl(DASHBOARD_URL)
        } else {
            webView.restoreState(savedInstanceState)
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
                traceFullscreen("SHOW_CUSTOM_VIEW")
                if (fullscreenVideoView != null) {
                    traceFullscreen("REJECT_SECOND_VIEW")
                    callback.onCustomViewHidden()
                    return
                }

                cancelPendingOrientationRestore()
                if (orientationBeforeFullscreen == null) {
                    orientationBeforeFullscreen = requestedOrientation
                }
                fullscreenVideoView = view
                fullscreenVideoCallback = callback
                window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
                window.addContentView(
                    view,
                    FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        Gravity.CENTER,
                    ),
                )
                traceFullscreen("VIEW_ATTACHED")
                loadingIndicator.visibility = View.GONE
                scheduleFullscreenOrientation(view)
            }

            override fun onHideCustomView() {
                traceFullscreen("HIDE_CUSTOM_VIEW")
                hideFullscreenVideo()
            }
        }
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

    private fun hideFullscreenVideo(
        restoreOrientationAfterDelay: Boolean = true,
        notifyWebContent: Boolean = false,
    ) {
        val view = fullscreenVideoView ?: return
        val callback = fullscreenVideoCallback
        cancelPendingFullscreenOrientation()
        (view.parent as? ViewGroup)?.removeView(view)
        fullscreenVideoView = null
        fullscreenVideoCallback = null
        window.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        if (notifyWebContent) {
            callback?.onCustomViewHidden()
        }

        if (!restoreOrientationAfterDelay) {
            orientationBeforeFullscreen = null
            return
        }

        scheduleOrientationRestore()
    }

    private fun prepareFullscreenOrientation() {
        cancelPendingFullscreenOrientation()
        cancelPendingOrientationRestore()
        if (orientationBeforeFullscreen == null) {
            orientationBeforeFullscreen = requestedOrientation
        }
        traceFullscreen("REQUEST_LANDSCAPE")
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
    }

    private fun scheduleFullscreenOrientation(view: View) {
        cancelPendingFullscreenOrientation()
        val requestOrientation = Runnable {
            pendingFullscreenOrientation = null
            if (fullscreenVideoView === view) {
                traceFullscreen("ORIENTATION_DELAY_ELAPSED")
                prepareFullscreenOrientation()
            }
        }
        pendingFullscreenOrientation = requestOrientation
        view.post {
            if (pendingFullscreenOrientation === requestOrientation) {
                mainHandler.postDelayed(requestOrientation, FULLSCREEN_ORIENTATION_DELAY_MS)
            }
        }
    }

    private fun cancelPendingFullscreenOrientation() {
        pendingFullscreenOrientation?.let(mainHandler::removeCallbacks)
        pendingFullscreenOrientation = null
    }

    private fun scheduleOrientationRestore() {
        val orientationToRestore = orientationBeforeFullscreen ?: return
        val restoreOrientation = Runnable {
            pendingOrientationRestore = null
            if (fullscreenVideoView == null) {
                traceFullscreen("RESTORE_ORIENTATION")
                requestedOrientation = orientationToRestore
                orientationBeforeFullscreen = null
            }
        }
        pendingOrientationRestore = restoreOrientation
        mainHandler.postDelayed(restoreOrientation, ORIENTATION_RESTORE_DELAY_MS)
    }

    private fun cancelPendingOrientationRestore() {
        pendingOrientationRestore?.let(mainHandler::removeCallbacks)
        pendingOrientationRestore = null
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val orientation = when (newConfig.orientation) {
            Configuration.ORIENTATION_LANDSCAPE -> "LANDSCAPE"
            Configuration.ORIENTATION_PORTRAIT -> "PORTRAIT"
            else -> "UNKNOWN"
        }
        traceFullscreen("CONFIG_$orientation")
    }

    private fun traceFullscreen(event: String) {
        val orientation =
            if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
                "landscape"
            } else {
                "portrait"
            }
        val entry =
            "${SystemClock.elapsedRealtime()}: $event / $orientation / " +
                "requested=$requestedOrientation / custom=${fullscreenVideoView != null}"
        fullscreenTraceLines += entry
        if (fullscreenTraceLines.size > 8) fullscreenTraceLines.removeAt(0)

        val traceView = fullscreenTraceView ?: TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 10f
            setBackgroundColor(Color.argb(220, 10, 18, 32))
            setPadding(10, 8, 10, 8)
            isClickable = false
        }.also {
            fullscreenTraceView = it
            window.addContentView(
                it,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.TOP,
                ),
            )
        }
        traceView.text = "Diagnostic plein écran\n${fullscreenTraceLines.joinToString("\n")}"
        traceView.visibility = View.VISIBLE
        traceView.bringToFront()
        mainHandler.removeCallbacks(hideFullscreenTrace)
        mainHandler.postDelayed(hideFullscreenTrace, FULLSCREEN_TRACE_VISIBLE_MS)
    }

    private fun openExternalUrl(url: Uri) {
        startActivity(Intent(Intent.ACTION_VIEW, url))
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (fullscreenVideoView != null) {
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
        cancelPendingFullscreenOrientation()
        cancelPendingOrientationRestore()
        hideFullscreenVideo(restoreOrientationAfterDelay = false)
        orientationBeforeFullscreen = null
        webView.destroy()
        super.onDestroy()
    }

    private companion object {
        const val DASHBOARD_URL = "https://parapente.capic.ignorelist.com"
        const val DASHBOARD_HOST = "parapente.capic.ignorelist.com"
        const val LOCATION_PERMISSION_REQUEST_CODE = 1001
        const val FULLSCREEN_ORIENTATION_DELAY_MS = 250L
        const val FULLSCREEN_TRACE_VISIBLE_MS = 15000L
        const val ORIENTATION_RESTORE_DELAY_MS = 800L
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
