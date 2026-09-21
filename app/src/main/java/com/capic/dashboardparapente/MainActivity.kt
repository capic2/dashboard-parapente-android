package com.capic.dashboardparapente

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ProgressBar
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout

class MainActivity : Activity() {
    private lateinit var webView: WebView
    private lateinit var loadingIndicator: ProgressBar
    private lateinit var swipeRefreshLayout: SwipeRefreshLayout
    private lateinit var rootView: FrameLayout
    private var fullscreenVideoView: View? = null
    private var fullscreenVideoCallback: WebChromeClient.CustomViewCallback? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        rootView = findViewById(R.id.root_view)
        webView = findViewById(R.id.dashboard_web_view)
        loadingIndicator = findViewById(R.id.loading_indicator)
        swipeRefreshLayout = findViewById(R.id.swipe_refresh_layout)
        swipeRefreshLayout.setOnRefreshListener { webView.reload() }
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
            mediaPlaybackRequiresUserGesture = true
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

            override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                if (fullscreenVideoView != null) {
                    callback.onCustomViewHidden()
                    return
                }

                fullscreenVideoView = view
                fullscreenVideoCallback = callback
                rootView.addView(
                    view,
                    FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    ),
                )
                webView.visibility = View.GONE
                loadingIndicator.visibility = View.GONE
                window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
            }

            override fun onHideCustomView() {
                hideFullscreenVideo()
            }
        }
    }

    private fun hideFullscreenVideo() {
        fullscreenVideoView?.let(rootView::removeView)
        fullscreenVideoView = null
        fullscreenVideoCallback?.onCustomViewHidden()
        fullscreenVideoCallback = null
        webView.visibility = View.VISIBLE
        window.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
    }

    private fun openExternalUrl(url: Uri) {
        startActivity(Intent(Intent.ACTION_VIEW, url))
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (fullscreenVideoView != null) {
            hideFullscreenVideo()
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
    }
}
