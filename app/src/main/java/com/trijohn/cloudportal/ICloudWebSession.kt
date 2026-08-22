package com.trijohn.cloudportal

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.PermissionRequest
import android.webkit.SafeBrowsingResponse
import android.webkit.SslErrorHandler
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit

class ICloudWebSession(private val activity: Activity) {
    val webView: WebView = WebView(activity)

    var currentUrl by mutableStateOf(ICLOUD_HOME)
        private set
    var pageTitle by mutableStateOf("iCloud")
        private set
    var progress by mutableIntStateOf(0)
        private set
    var isLoading by mutableStateOf(false)
        private set
    var canGoBack by mutableStateOf(false)
        private set
    var canGoForward by mutableStateOf(false)
        private set
    var hasStoredSession by mutableStateOf(CookieManager.getInstance().hasCookies())
        private set

    var onMessage: ((String) -> Unit)? = null
    var onDownloadRequested: ((WebDownloadRequest) -> Unit)? = null
    var onFileChooserRequested: ((ValueCallback<Array<Uri>>, WebChromeClient.FileChooserParams) -> Unit)? = null

    private val preferences = activity.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    init {
        configureWebView()
        val restoredUrl = preferences.getString(KEY_LAST_URL, null)
            ?.takeIf(ICloudUrlPolicy::isAllowed)
            ?: ICLOUD_HOME
        load(restoredUrl)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebView() {
        WebView.setWebContentsDebuggingEnabled(false)

        webView.settings.apply {
            // iCloud.com is a JavaScript web application and needs DOM storage for its session state.
            javaScriptEnabled = true
            domStorageEnabled = true
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)

            allowFileAccess = false
            allowContentAccess = true // Required only for the system file picker used by uploads.
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            safeBrowsingEnabled = true

            cacheMode = WebSettings.LOAD_DEFAULT
            loadsImagesAutomatically = true
            mediaPlaybackRequiresUserGesture = true
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false

            // Render as mobile Chromium. The stock WebView marker makes some full websites
            // incorrectly serve a reduced or unsupported page even though the engine is Chromium.
            userAgentString = userAgentString
                .replace("; wv", "")
                .replace("Version/4.0 ", "")
        }

        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, true)
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val target = request.url
                if (ICloudUrlPolicy.isAllowed(target.toString())) return false
                openOutsideApp(target)
                return true
            }

            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                isLoading = true
                updateNavigationState(view, url)
            }

            override fun onPageFinished(view: WebView, url: String) {
                isLoading = false
                progress = 100
                updateNavigationState(view, url)
                repairCollapsedICloudViewport(view)
                if (ICloudUrlPolicy.isAllowed(url)) {
                    preferences.edit { putString(KEY_LAST_URL, url) }
                }
                persistSession()
            }

            override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
                updateNavigationState(view, url)
            }

            override fun onReceivedError(
                view: WebView,
                request: WebResourceRequest,
                error: WebResourceError,
            ) {
                if (request.isForMainFrame) {
                    isLoading = false
                    onMessage?.invoke("Không thể tải iCloud. Kiểm tra kết nối rồi thử lại.")
                }
            }

            override fun onReceivedHttpError(
                view: WebView,
                request: WebResourceRequest,
                errorResponse: WebResourceResponse,
            ) {
                if (request.isForMainFrame && errorResponse.statusCode >= 500) {
                    onMessage?.invoke("Máy chủ iCloud đang gặp sự cố (${errorResponse.statusCode}).")
                }
            }

            override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
                handler.cancel()
                onMessage?.invoke("Đã chặn trang vì chứng chỉ bảo mật không hợp lệ.")
            }

            override fun onSafeBrowsingHit(
                view: WebView,
                request: WebResourceRequest,
                threatType: Int,
                callback: SafeBrowsingResponse,
            ) {
                callback.backToSafety(true)
                onMessage?.invoke("Safe Browsing đã chặn một trang không an toàn.")
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                progress = newProgress
                isLoading = newProgress < 100
            }

            override fun onReceivedTitle(view: WebView, title: String?) {
                pageTitle = title?.takeIf { it.isNotBlank() } ?: "iCloud"
            }

            override fun onShowFileChooser(
                webView: WebView,
                filePathCallback: ValueCallback<Array<Uri>>,
                fileChooserParams: FileChooserParams,
            ): Boolean {
                val handler = onFileChooserRequested ?: return false
                handler(filePathCallback, fileChooserParams)
                return true
            }

            override fun onPermissionRequest(request: PermissionRequest) {
                // Photos and Drive do not require camera or microphone permissions.
                request.deny()
            }
        }

        webView.setDownloadListener { url, userAgent, contentDisposition, mimeType, contentLength ->
            if (!ICloudUrlPolicy.isAllowed(url)) {
                onMessage?.invoke("Đã chặn liên kết tải xuống không thuộc máy chủ Apple.")
                return@setDownloadListener
            }
            onDownloadRequested?.invoke(
                WebDownloadRequest(
                    url = url,
                    userAgent = userAgent.orEmpty(),
                    contentDisposition = contentDisposition,
                    mimeType = mimeType,
                    contentLength = contentLength,
                ),
            )
        }
    }

    fun load(url: String) {
        if (!ICloudUrlPolicy.isAllowed(url)) {
            onMessage?.invoke("Cloud Portal chỉ mở trang HTTPS chính thức của Apple.")
            return
        }
        webView.loadUrl(url)
    }

    fun goBack() {
        if (webView.canGoBack()) webView.goBack()
    }

    fun goForward() {
        if (webView.canGoForward()) webView.goForward()
    }

    fun reload() {
        webView.reload()
    }

    fun stopLoading() {
        webView.stopLoading()
    }

    fun onWebViewAttached() {
        webView.post {
            webView.requestLayout()
            repairCollapsedICloudViewport(webView)
        }
    }

    fun persistSession() {
        CookieManager.getInstance().flush()
        hasStoredSession = CookieManager.getInstance().hasCookies()
    }

    fun clearSession(onComplete: () -> Unit) {
        webView.stopLoading()
        CookieManager.getInstance().removeAllCookies {
            CookieManager.getInstance().flush()
            WebStorage.getInstance().deleteAllData()
            webView.clearCache(true)
            webView.clearFormData()
            webView.clearHistory()
            webView.clearSslPreferences()
            preferences.edit { remove(KEY_LAST_URL) }
            currentUrl = ICLOUD_HOME
            pageTitle = "iCloud"
            hasStoredSession = false
            webView.loadUrl(ICLOUD_HOME)
            onComplete()
        }
    }

    fun onResume() {
        webView.onResume()
    }

    fun onPause() {
        persistSession()
        webView.onPause()
    }

    fun destroy() {
        persistSession()
        webView.stopLoading()
        (webView.parent as? ViewGroup)?.removeView(webView)
        webView.webChromeClient = null
        webView.webViewClient = WebViewClient()
        webView.destroy()
    }

    private fun updateNavigationState(view: WebView, url: String) {
        currentUrl = url
        canGoBack = view.canGoBack()
        canGoForward = view.canGoForward()
    }

    /**
     * iCloud's mobile layout can resolve its percentage-based root height to 0 inside an embedded
     * WebView. The page is fully loaded in that state, but all visible content is clipped. This
     * compatibility patch activates only after detecting a collapsed iCloud root and follows
     * viewport changes caused by rotation or the software keyboard.
     */
    private fun repairCollapsedICloudViewport(view: WebView) {
        view.evaluateJavascript(VIEWPORT_COMPATIBILITY_SCRIPT, null)
    }

    private fun openOutsideApp(uri: Uri) {
        if (uri.scheme !in setOf("https", "http", "mailto", "tel")) {
            onMessage?.invoke("Đã chặn liên kết ngoài không được hỗ trợ.")
            return
        }
        try {
            activity.startActivity(Intent(Intent.ACTION_VIEW, uri))
        } catch (_: ActivityNotFoundException) {
            onMessage?.invoke("Không tìm thấy ứng dụng để mở liên kết này.")
        }
    }

    companion object {
        const val ICLOUD_HOME = "https://www.icloud.com/"
        const val PHOTOS_URL = "https://www.icloud.com/photos/"
        const val DRIVE_URL = "https://www.icloud.com/iclouddrive/"
        const val NOTES_URL = "https://www.icloud.com/notes/"

        private const val PREFERENCES = "cloud_portal_session"
        private const val KEY_LAST_URL = "last_trusted_url"

        private val VIEWPORT_COMPATIBILITY_SCRIPT = """
            (() => {
              const key = '__cloudPortalViewportCompatibility';
              if (window[key]) {
                window[key].schedule();
                return;
              }

              const state = {
                active: false,
                pending: false,
                apply() {
                  state.pending = false;
                  const viewportHeight = Math.floor(Math.max(
                    window.innerHeight || 0,
                    window.visualViewport?.height || 0
                  ));
                  if (viewportHeight < 1) return;

                  const elements = [
                    document.documentElement,
                    document.body,
                    document.querySelector('#root'),
                    document.querySelector('ui-main-pane'),
                    document.querySelector('.root-viewport'),
                    document.querySelector('.root-component'),
                    document.querySelector('.page-viewport')
                  ].filter(Boolean);
                  const isCollapsed = elements.some(
                    element => element.getBoundingClientRect().height < 1
                  );
                  if (!state.active && !isCollapsed) return;

                  state.active = true;
                  const height = `${'$'}{viewportHeight}px`;
                  for (const element of elements) {
                    element.style.setProperty('height', height, 'important');
                  }
                },
                schedule() {
                  if (state.pending) return;
                  state.pending = true;
                  window.requestAnimationFrame(state.apply);
                }
              };

              window[key] = state;
              window.addEventListener('resize', state.schedule, { passive: true });
              window.visualViewport?.addEventListener('resize', state.schedule, { passive: true });
              new MutationObserver(state.schedule).observe(document.documentElement, {
                childList: true,
                subtree: true
              });
              state.schedule();
            })();
        """.trimIndent()
    }
}
