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
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class ICloudWebSession(private val activity: Activity) {
    val webView: WebView = StableSwipeWebView(activity)

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
    internal var onBlobDownloadRequested: ((WebDownloadRequest) -> Result<BlobDownloadSink>)? = null
    var onFileChooserRequested: ((ValueCallback<Array<Uri>>, WebChromeClient.FileChooserParams) -> Unit)? = null

    private val preferences = activity.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
    private val blobDownloadExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private var activeBlobDownload: ActiveBlobDownload? = null
    private var blobBridgeSupported = false
    private var blobVaultRunsAtDocumentStart = false

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

        configureBlobDownloadBridge()

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
                if (blobBridgeSupported && !blobVaultRunsAtDocumentStart) {
                    view.evaluateJavascript(BLOB_VAULT_SCRIPT, null)
                }
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
            val details = WebDownloadRequest(
                url = url,
                userAgent = userAgent.orEmpty(),
                contentDisposition = contentDisposition,
                mimeType = mimeType,
                contentLength = contentLength,
            )
            if (ICloudUrlPolicy.isTrustedBlob(url)) {
                startBlobDownload(details)
                return@setDownloadListener
            }
            if (!ICloudUrlPolicy.isAllowed(url)) {
                onMessage?.invoke(
                    "Không thể tải từ nguồn ${ICloudUrlPolicy.sourceLabel(url)}. Cloud Portal chỉ nhận nguồn iCloud chính thức.",
                )
                return@setDownloadListener
            }
            onDownloadRequested?.invoke(details)
        }
    }

    private fun configureBlobDownloadBridge() {
        blobBridgeSupported = WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER) &&
            WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_ARRAY_BUFFER)
        if (!blobBridgeSupported) return

        blobVaultRunsAtDocumentStart = WebViewFeature.isFeatureSupported(
            WebViewFeature.DOCUMENT_START_SCRIPT,
        )
        if (blobVaultRunsAtDocumentStart) {
            WebViewCompat.addDocumentStartJavaScript(
                webView,
                BLOB_VAULT_SCRIPT,
                TRUSTED_BLOB_ORIGINS,
            )
        }

        WebViewCompat.addWebMessageListener(
            webView,
            BLOB_BRIDGE_NAME,
            TRUSTED_BLOB_ORIGINS,
        ) { view, message, sourceOrigin, isMainFrame, replyProxy ->
            if (!isMainFrame || sourceOrigin.toString().trimEnd('/') !in TRUSTED_BLOB_ORIGINS) return@addWebMessageListener
            handleBlobBridgeMessage(view, message, replyProxy)
        }
    }

    private fun startBlobDownload(details: WebDownloadRequest) {
        if (!blobBridgeSupported) {
            onMessage?.invoke("Android System WebView cần được cập nhật để tải video này từ iCloud.")
            return
        }
        if (activeBlobDownload != null) {
            onMessage?.invoke("Hãy đợi tệp hiện tại tải xong trước khi tải video tiếp theo.")
            return
        }

        val createSink = onBlobDownloadRequested
        if (createSink == null) {
            onMessage?.invoke("Bộ tải xuống chưa sẵn sàng.")
            return
        }
        createSink(details)
            .onFailure { error ->
                onMessage?.invoke(error.message ?: "Không thể tạo tệp trong Downloads.")
            }
            .onSuccess { sink ->
                val transfer = ActiveBlobDownload(UUID.randomUUID().toString(), sink)
                activeBlobDownload = transfer
                webView.keepScreenOn = true
                onMessage?.invoke("Đang tải ${sink.fileName} vào thư mục Downloads.")
                webView.evaluateJavascript(createBlobTransferScript(details.url, transfer.token)) { result ->
                    if (result == JSONObject.quote(BLOB_BRIDGE_MISSING_RESULT)) {
                        failBlobDownload(transfer, "Không thể kết nối bộ tải video với trang iCloud.")
                    }
                }
            }
    }

    private fun handleBlobBridgeMessage(
        view: WebView,
        message: WebMessageCompat,
        replyProxy: JavaScriptReplyProxy,
    ) {
        val transfer = activeBlobDownload ?: return
        when (message.type) {
            WebMessageCompat.TYPE_ARRAY_BUFFER -> {
                val bytes = message.arrayBuffer
                runBlobOperation(view, replyProxy, transfer, acknowledge = true) {
                    transfer.sink.write(bytes)
                }
            }

            WebMessageCompat.TYPE_STRING -> handleBlobControlMessage(
                view,
                message.data.orEmpty(),
                replyProxy,
                transfer,
            )
        }
    }

    private fun handleBlobControlMessage(
        view: WebView,
        value: String,
        replyProxy: JavaScriptReplyProxy,
        transfer: ActiveBlobDownload,
    ) {
        when {
            value.startsWith("size:${transfer.token}:") -> {
                val totalBytes = value.substringAfterLast(':').toLongOrNull() ?: return
                runBlobOperation(view, replyProxy, transfer, acknowledge = true) {
                    transfer.sink.updateTotalBytes(totalBytes)
                }
            }

            value == "finish:${transfer.token}" -> {
                runBlobOperation(view, replyProxy, transfer, acknowledge = false) {
                    transfer.sink.complete()
                }
            }

            value == "error:${transfer.token}" -> {
                failBlobDownload(transfer, "iCloud không thể chuẩn bị dữ liệu tải xuống. Hãy thử lại.")
            }
        }
    }

    private fun runBlobOperation(
        view: WebView,
        replyProxy: JavaScriptReplyProxy,
        transfer: ActiveBlobDownload,
        acknowledge: Boolean,
        operation: () -> Result<Unit>,
    ) {
        blobDownloadExecutor.execute {
            val result = operation()
            view.post {
                if (activeBlobDownload?.token != transfer.token) return@post
                result.onSuccess {
                    if (acknowledge) {
                        replyProxy.postMessage("next:${transfer.token}")
                    } else {
                        activeBlobDownload = null
                        webView.keepScreenOn = false
                        onMessage?.invoke("Đã tải xong ${transfer.sink.fileName}.")
                    }
                }.onFailure { error ->
                    runCatching { replyProxy.postMessage("abort:${transfer.token}") }
                    failBlobDownload(
                        transfer,
                        error.message ?: "Không thể lưu tệp từ iCloud vào Downloads.",
                    )
                }
            }
        }
    }

    private fun failBlobDownload(transfer: ActiveBlobDownload, reason: String) {
        if (activeBlobDownload?.token != transfer.token) return
        activeBlobDownload = null
        webView.keepScreenOn = false
        blobDownloadExecutor.execute { transfer.sink.fail() }
        onMessage?.invoke(reason)
    }

    private fun createBlobTransferScript(blobUrl: String, token: String): String {
        val quotedUrl = JSONObject.quote(blobUrl)
        val quotedToken = JSONObject.quote(token)
        return """
            (() => {
              const bridge = window.$BLOB_BRIDGE_NAME;
              if (!bridge || typeof bridge.postMessage !== 'function') return '$BLOB_BRIDGE_MISSING_RESULT';
              const blobUrl = $quotedUrl;
              const token = $quotedToken;
              let pending = null;
              bridge.onmessage = event => {
                if (!pending) return;
                const value = String(event.data || '');
                if (value === `next:${'$'}{token}`) {
                  const resolve = pending.resolve;
                  clearTimeout(pending.timeout);
                  pending = null;
                  resolve();
                } else if (value === `abort:${'$'}{token}`) {
                  const reject = pending.reject;
                  clearTimeout(pending.timeout);
                  pending = null;
                  reject(new Error('Native download aborted'));
                }
              };
              const sendAndWait = payload => new Promise((resolve, reject) => {
                const timeout = setTimeout(() => {
                  pending = null;
                  reject(new Error('Native download timed out'));
                }, $BLOB_ACK_TIMEOUT_MILLIS);
                pending = { resolve, reject, timeout };
                bridge.postMessage(payload);
              });
              (async () => {
                try {
                  let blob = window.$BLOB_VAULT_NAME?.take?.(blobUrl);
                  if (!(blob instanceof Blob)) {
                    const response = await fetch(blobUrl);
                    if (!response.ok) throw new Error(`HTTP ${'$'}{response.status}`);
                    blob = await response.blob();
                  }
                  await sendAndWait(`size:${'$'}{token}:${'$'}{blob.size}`);
                  for (let offset = 0; offset < blob.size;) {
                    const buffer = await blob.slice(offset, offset + $BLOB_CHUNK_BYTES).arrayBuffer();
                    await sendAndWait(buffer);
                    offset += buffer.byteLength;
                  }
                  bridge.postMessage(`finish:${'$'}{token}`);
                } catch (_) {
                  bridge.postMessage(`error:${'$'}{token}`);
                }
              })();
              return 'started';
            })();
        """.trimIndent()
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
        activeBlobDownload?.let { transfer ->
            activeBlobDownload = null
            blobDownloadExecutor.execute { transfer.sink.fail() }
        }
        webView.keepScreenOn = false
        blobDownloadExecutor.shutdown()
        (webView.parent as? ViewGroup)?.removeView(webView)
        webView.webChromeClient = null
        webView.webViewClient = WebViewClient()
        webView.destroy()
    }

    private fun updateNavigationState(view: WebView, url: String) {
        currentUrl = url
        canGoBack = view.canGoBack()
        canGoForward = view.canGoForward()
        (view as? StableSwipeWebView)?.nativePhotoPagingEnabled =
            url.contains("/photos", ignoreCase = true)
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
        private const val BLOB_BRIDGE_NAME = "CloudPortalNativeDownloads"
        private const val BLOB_VAULT_NAME = "__cloudPortalBlobVault"
        private const val BLOB_BRIDGE_MISSING_RESULT = "bridge-missing"
        private const val BLOB_CHUNK_BYTES = 512 * 1024
        private const val BLOB_ACK_TIMEOUT_MILLIS = 30_000
        private const val BLOB_URL_RETENTION_MILLIS = 2 * 60_000
        private val TRUSTED_BLOB_ORIGINS = setOf(
            "https://www.icloud.com",
            "https://www.icloud.com.cn",
        )

        private val BLOB_VAULT_SCRIPT = """
            (() => {
              if (window.$BLOB_VAULT_NAME) return;
              const tracked = new Map();
              const createObjectURL = URL.createObjectURL.bind(URL);
              const revokeObjectURL = URL.revokeObjectURL.bind(URL);
              URL.createObjectURL = value => {
                const url = createObjectURL(value);
                if (value instanceof Blob) tracked.set(url, value);
                return url;
              };
              URL.revokeObjectURL = value => {
                const url = String(value);
                if (!tracked.has(url)) {
                  revokeObjectURL(value);
                  return;
                }
                setTimeout(() => {
                  tracked.delete(url);
                  revokeObjectURL(url);
                }, $BLOB_URL_RETENTION_MILLIS);
              };
              Object.defineProperty(window, '$BLOB_VAULT_NAME', {
                configurable: false,
                enumerable: false,
                value: {
                  take(url) {
                    const value = tracked.get(url);
                    tracked.delete(url);
                    return value;
                  }
                }
              });
            })();
        """.trimIndent()

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

    private data class ActiveBlobDownload(
        val token: String,
        val sink: BlobDownloadSink,
    )
}
