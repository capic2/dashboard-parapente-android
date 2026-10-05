package com.capic.dashboardparapente

import android.app.Activity
import android.content.Intent
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
    private var fullscreenUiVisibilityBefore: Int? = null
    private var fullscreenLandscapeLayoutListener: View.OnLayoutChangeListener? = null
    private var fullscreenLayoutTimeout: Runnable? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var fullscreenTraceView: TextView? = null
    private val fullscreenTraceLines = mutableListOf<String>()
    private val hideFullscreenTrace = Runnable { fullscreenTraceView?.visibility = View.GONE }
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
                traceFullscreen("SHOW_CUSTOM_VIEW ${view.javaClass.simpleName} ${view.width}x${view.height}")
                if (fullscreenVideoView != null) {
                    traceFullscreen("REJECT_SECOND_VIEW")
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
                traceFullscreen("VIEW_ATTACHED ${view.width}x${view.height} parent=${(view.parent as? View)?.width}x${(view.parent as? View)?.height}")
                scheduleLandscapePresentation(view)
            }

            override fun onHideCustomView() {
                val view = fullscreenVideoView
                traceFullscreen("HIDE_CUSTOM_VIEW ${view?.width}x${view?.height} rot=${view?.rotation}")
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

    private fun hideFullscreenVideo(notifyWebContent: Boolean = false) {
        val view = fullscreenVideoView ?: return
        val callback = fullscreenVideoCallback
        traceFullscreen("REMOVE_CUSTOM_VIEW ${view.width}x${view.height} rot=${view.rotation}")
        fullscreenLandscapeLayoutListener?.let(view::removeOnLayoutChangeListener)
        fullscreenLandscapeLayoutListener = null
        fullscreenLayoutTimeout?.let(view::removeCallbacks)
        fullscreenLayoutTimeout = null
        view.rotation = 0f
        (view.layoutParams as? FrameLayout.LayoutParams)?.let { layoutParams ->
            layoutParams.width = ViewGroup.LayoutParams.MATCH_PARENT
            layoutParams.height = ViewGroup.LayoutParams.MATCH_PARENT
            layoutParams.gravity = Gravity.CENTER
            view.layoutParams = layoutParams
        }
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

    private fun scheduleLandscapePresentation(view: View) {
        val parent = view.parent as? ViewGroup ?: return
        val parentWidth = parent.width
        val parentHeight = parent.height
        traceFullscreen("LAYOUT_START parent=${parentWidth}x${parentHeight} view=${view.width}x${view.height}")
        if (parentWidth <= 0 || parentHeight <= 0) {
            view.postDelayed({ scheduleLandscapePresentation(view) }, FULLSCREEN_LAYOUT_RETRY_DELAY_MS)
            return
        }
        if (fullscreenVideoView !== view) return

        val targetWidth = parentHeight
        val targetHeight = parentWidth
        traceFullscreen("LAYOUT_TARGET ${targetWidth}x${targetHeight}")
        val layoutParams = view.layoutParams as? FrameLayout.LayoutParams
            ?: FrameLayout.LayoutParams(targetWidth, targetHeight, Gravity.CENTER)
        layoutParams.width = targetWidth
        layoutParams.height = targetHeight
        layoutParams.gravity = Gravity.CENTER

        view.visibility = View.INVISIBLE
        view.rotation = 0f

        fun applyLandscapeTransform() {
            fullscreenLandscapeLayoutListener?.let(view::removeOnLayoutChangeListener)
            fullscreenLandscapeLayoutListener = null
            fullscreenLayoutTimeout?.let(view::removeCallbacks)
            fullscreenLayoutTimeout = null
            view.pivotX = view.width / 2f
            view.pivotY = view.height / 2f
            view.rotation = LANDSCAPE_VIEW_ROTATION_DEGREES
            view.visibility = View.VISIBLE
            traceFullscreen("LANDSCAPE_APPLIED ${view.width}x${view.height} rot=${view.rotation}")
        }

        if (view.width == targetWidth && view.height == targetHeight) {
            applyLandscapeTransform()
            view.layoutParams = layoutParams
            return
        }

        val layoutListener = View.OnLayoutChangeListener { laidOutView, _, _, _, _, _, _, _, _ ->
            if (laidOutView.width == targetWidth && laidOutView.height == targetHeight) {
                applyLandscapeTransform()
            }
        }
        fullscreenLandscapeLayoutListener = layoutListener
        view.addOnLayoutChangeListener(layoutListener)
        view.layoutParams = layoutParams

        val timeout = Runnable {
            if (fullscreenVideoView === view && view.visibility != View.VISIBLE) {
                traceFullscreen("LAYOUT_TIMEOUT ${view.width}x${view.height} parent=${parent.width}x${parent.height}")
                fullscreenLandscapeLayoutListener?.let(view::removeOnLayoutChangeListener)
                fullscreenLandscapeLayoutListener = null
                view.rotation = 0f
                view.layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    Gravity.CENTER,
                )
                view.visibility = View.VISIBLE
            }
            fullscreenLayoutTimeout = null
        }
        fullscreenLayoutTimeout = timeout
        view.postDelayed(timeout, FULLSCREEN_LAYOUT_TIMEOUT_MS)
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
            if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) "landscape" else "portrait"
        fullscreenTraceLines += "${SystemClock.elapsedRealtime()}: $event / $orientation / requested=$requestedOrientation"
        if (fullscreenTraceLines.size > 7) fullscreenTraceLines.removeAt(0)

        val traceView = fullscreenTraceView ?: TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 10f
            setBackgroundColor(Color.argb(225, 10, 18, 32))
            setPadding(10, 8, 10, 8)
            isClickable = false
            elevation = 100f
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
        traceView.text = "Diagnostic 1.0.23\n${fullscreenTraceLines.joinToString("\n")}"
        traceView.visibility = View.VISIBLE
        traceView.bringToFront()
        mainHandler.removeCallbacks(hideFullscreenTrace)
        mainHandler.postDelayed(hideFullscreenTrace, FULLSCREEN_TRACE_DURATION_MS)
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
        hideFullscreenVideo()
        webView.destroy()
        super.onDestroy()
    }

    private companion object {
        const val DASHBOARD_URL = "https://parapente.capic.ignorelist.com"
        const val DASHBOARD_HOST = "parapente.capic.ignorelist.com"
        const val LOCATION_PERMISSION_REQUEST_CODE = 1001
        const val LANDSCAPE_VIEW_ROTATION_DEGREES = 90f
        const val FULLSCREEN_LAYOUT_RETRY_DELAY_MS = 16L
        const val FULLSCREEN_LAYOUT_TIMEOUT_MS = 1000L
        const val FULLSCREEN_TRACE_DURATION_MS = 30000L
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
