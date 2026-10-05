package com.capic.dashboardparapente

import android.app.Activity
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ProgressBar
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import java.util.Locale

class MainActivity : Activity() {
    private lateinit var webView: WebView
    private lateinit var loadingIndicator: ProgressBar
    private lateinit var swipeRefreshLayout: SwipeRefreshLayout
    private var fullscreenVideoView: View? = null
    private var fullscreenVideoCallback: WebChromeClient.CustomViewCallback? = null
    private var fullscreenUiVisibilityBefore: Int? = null
    private var orientationBeforeFullscreen: Int? = null
    private var awaitingLandscapeFullscreenRequest = false
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
        webView.addJavascriptInterface(FullscreenOrientationBridge(), VIDEO_FULLSCREEN_BRIDGE_NAME)

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                swipeRefreshLayout.isRefreshing = false
                if (Uri.parse(url).host == DASHBOARD_HOST) {
                    view.evaluateJavascript(FULLSCREEN_ORIENTATION_BRIDGE_SCRIPT, null)
                }
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
                if (fullscreenVideoView != null) {
                    callback.onCustomViewHidden()
                    return
                }

                fullscreenVideoView = view
                fullscreenVideoCallback = callback
                if (orientationBeforeFullscreen == null) {
                    orientationBeforeFullscreen = requestedOrientation
                }
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
        (view.parent as? ViewGroup)?.removeView(view)
        fullscreenVideoView = null
        fullscreenVideoCallback = null
        awaitingLandscapeFullscreenRequest = false
        orientationBeforeFullscreen?.let { requestedOrientation = it }
        orientationBeforeFullscreen = null
        window.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        fullscreenUiVisibilityBefore?.let { previousVisibility ->
            window.decorView.systemUiVisibility = previousVisibility
        }
        fullscreenUiVisibilityBefore = null
        if (notifyWebContent) {
            callback?.onCustomViewHidden()
        }
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        if (awaitingLandscapeFullscreenRequest &&
            newConfig.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
        ) {
            awaitingLandscapeFullscreenRequest = false
            webView.post {
                webView.evaluateJavascript("window.__dashboardRequestFullscreenAfterRotation?.()", null)
            }
        }
    }

    private inner class FullscreenOrientationBridge {
        @JavascriptInterface
        fun isLandscape(): Boolean =
            isDashboardLoaded() &&
                resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE

        @JavascriptInterface
        fun prepareLandscape() {
            runOnUiThread {
                if (!isDashboardLoaded()) return@runOnUiThread
                if (orientationBeforeFullscreen == null) {
                    orientationBeforeFullscreen = requestedOrientation
                }
                awaitingLandscapeFullscreenRequest = true
                requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            }
        }

        @JavascriptInterface
        fun cancelLandscape() {
            runOnUiThread {
                if (!isDashboardLoaded()) return@runOnUiThread
                awaitingLandscapeFullscreenRequest = false
                orientationBeforeFullscreen?.let { requestedOrientation = it }
                orientationBeforeFullscreen = null
            }
        }

        private fun isDashboardLoaded(): Boolean =
            Uri.parse(webView.url).host == DASHBOARD_HOST
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
        const val VIDEO_FULLSCREEN_BRIDGE_NAME = "DashboardVideoOrientation"
        val FULLSCREEN_ORIENTATION_BRIDGE_SCRIPT = """
            (function() {
                const bridge = window.$VIDEO_FULLSCREEN_BRIDGE_NAME;
                const original = Element.prototype.requestFullscreen;
                if (!bridge || !original || original.__dashboardOrientationWrapped) return;

                const wrapped = function(options) {
                    let alreadyLandscape = false;
                    try { alreadyLandscape = bridge.isLandscape(); } catch (_) {}
                    if (alreadyLandscape) return original.call(this, options);

                    const target = this;
                    return new Promise((resolve, reject) => {
                        let settled = false;
                        let timeout = 0;
                        const cleanup = () => {
                            window.removeEventListener('resize', checkOrientation);
                            window.__dashboardRequestFullscreenAfterRotation = null;
                            if (timeout) clearTimeout(timeout);
                        };
                        const request = () => {
                            if (settled) return;
                            settled = true;
                            cleanup();
                            try {
                                Promise.resolve(original.call(target, options)).then(resolve, error => {
                                    try { bridge.cancelLandscape(); } catch (_) {}
                                    reject(error);
                                });
                            } catch (error) {
                                try { bridge.cancelLandscape(); } catch (_) {}
                                reject(error);
                            }
                        };
                        const checkOrientation = () => {
                            try { if (bridge.isLandscape()) request(); } catch (_) {}
                        };

                        window.__dashboardRequestFullscreenAfterRotation = request;
                        window.addEventListener('resize', checkOrientation);
                        timeout = setTimeout(() => {
                            try { bridge.cancelLandscape(); } catch (_) {}
                            reject(new DOMException('Landscape transition timed out', 'AbortError'));
                        }, 1800);
                        try { bridge.prepareLandscape(); } catch (_) { request(); }
                    });
                };
                wrapped.__dashboardOrientationWrapped = true;
                Element.prototype.requestFullscreen = wrapped;
            })();
        """.trimIndent()
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
