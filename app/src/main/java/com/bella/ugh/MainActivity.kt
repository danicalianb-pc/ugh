package com.bella.ugh

import android.Manifest
import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.browser.customtabs.CustomTabsIntent
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.updatePadding
import com.bella.ugh.databinding.ActivityMainBinding
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private var webView: WebView? = null

    private var mobileUserAgent: String = ""
    private var desktopUserAgent: String = ""
    private var desktopMode = false

    private var pendingFileChooser: ValueCallback<Array<Uri>>? = null
    private var pendingCameraUri: Uri? = null

    private var pendingMediaRequest: PermissionRequest? = null
    private var pendingGeoCallback: GeolocationPermissions.Callback? = null
    private var pendingGeoOrigin: String? = null
    private var pendingDownload: DownloadRequest? = null

    private var fullscreenView: View? = null
    private var fullscreenCallback: WebChromeClient.CustomViewCallback? = null

    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var lastBackPressAt = 0L
    private var pendingInitialUrl: String? = null
    private var restoredState: Bundle? = null

    private data class DownloadRequest(
        val url: String,
        val userAgent: String?,
        val contentDisposition: String?,
        val mimeType: String?,
        val contentLength: Long,
    )

    private val fileChooserLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val callback = pendingFileChooser
            pendingFileChooser = null
            val cameraUri = pendingCameraUri
            pendingCameraUri = null
            val data = result.data
            val uris: Array<Uri>? = when {
                result.resultCode != RESULT_OK -> null
                data?.clipData != null -> {
                    val clip = data.clipData!!
                    Array(clip.itemCount) { clip.getItemAt(it).uri }
                }
                data?.data != null -> arrayOf(data.data!!)
                cameraUri != null -> arrayOf(cameraUri)
                else -> null
            }
            callback?.onReceiveValue(uris)
        }

    private val mediaPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
            val request = pendingMediaRequest
            pendingMediaRequest = null
            if (request == null) return@registerForActivityResult
            if (grants.values.all { it }) {
                val granted = request.resources.filter { resource ->
                    when (resource) {
                        PermissionRequest.RESOURCE_VIDEO_CAPTURE -> hasPermission(Manifest.permission.CAMERA)
                        PermissionRequest.RESOURCE_AUDIO_CAPTURE -> hasPermission(Manifest.permission.RECORD_AUDIO)
                        else -> false
                    }
                }
                if (granted.isEmpty()) request.deny() else request.grant(granted.toTypedArray())
            } else {
                request.deny()
            }
        }

    private val locationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
            val callback = pendingGeoCallback
            val origin = pendingGeoOrigin
            pendingGeoCallback = null
            pendingGeoOrigin = null
            callback?.invoke(origin, grants.values.any { it }, false)
        }

    private val storagePermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            val request = pendingDownload
            pendingDownload = null
            if (request == null) return@registerForActivityResult
            if (granted) startDownload(request) else toast(getString(R.string.download_failed))
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        applyWindowInsets()

        if (BuildConfig.DEBUG) {
            WebView.setWebContentsDebuggingEnabled(true)
        }

        desktopMode = prefs().getBoolean(PREF_DESKTOP_MODE, false)

        binding.swipe.isEnabled = Config.SWIPE_TO_REFRESH
        binding.swipe.setOnRefreshListener { webView?.reload() }
        binding.swipe.setOnChildScrollUpCallback { _, _ -> (webView?.scrollY ?: 0) > 0 }
        binding.retryBtn.setOnClickListener { retry() }
        binding.menuBtn.setOnClickListener { showMenu() }

        wireBackPressed()

        restoredState = savedInstanceState?.getBundle(STATE_WEBVIEW)
        pendingInitialUrl = intent?.data?.takeIf { Config.isInternal(it) }?.toString() ?: Config.START_URL

        if (isOnline()) createWebView() else showOffline()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val uri = intent.data ?: return
        if (!Config.isInternal(uri)) return
        val target = uri.toString()
        if (target == webView?.url) return
        pendingInitialUrl = target
        hideOffline()
        val current = webView
        if (current == null) retry() else current.loadUrl(target)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        val state = Bundle()
        webView?.saveState(state)
        outState.putBundle(STATE_WEBVIEW, state)
    }

    override fun onDestroy() {
        unregisterNetworkCallback()
        destroyWebView()
        super.onDestroy()
    }

    fun isTrustedPage(): Boolean = Config.isInternal(webView?.url)

    fun reloadPage() {
        hideOffline()
        webView?.reload()
    }

    fun openInBrowser(uri: Uri) {
        if (Config.OPEN_EXTERNAL_LINKS_IN_CUSTOM_TAB) {
            try {
                CustomTabsIntent.Builder()
                    .setShowTitle(true)
                    .build()
                    .launchUrl(this, uri)
                return
            } catch (e: Exception) {
                Log.d(TAG, "Custom tab unavailable: ${e.message}")
            }
        }
        openExternalIntent(uri)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView() {
        if (webView != null) return
        hideOffline()

        val wv = WebView(this)
        wv.layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        )
        wv.isScrollbarFadingEnabled = true
        wv.overScrollMode = View.OVER_SCROLL_NEVER

        val settings = wv.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.databaseEnabled = true
        settings.javaScriptCanOpenWindowsAutomatically = true
        settings.setSupportMultipleWindows(false)
        settings.loadWithOverviewMode = true
        settings.useWideViewPort = true
        settings.setSupportZoom(true)
        settings.builtInZoomControls = true
        settings.displayZoomControls = false
        settings.mediaPlaybackRequiresUserGesture = false
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        settings.cacheMode = WebSettings.LOAD_DEFAULT
        settings.allowFileAccess = false
        settings.allowContentAccess = true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            settings.safeBrowsingEnabled = true
        }

        mobileUserAgent = defaultUserAgent()
        desktopUserAgent = Config.desktopUserAgent(mobileUserAgent)
        settings.userAgentString = if (desktopMode) desktopUserAgent else mobileUserAgent

        wv.addJavascriptInterface(WebAppBridge(this), JS_BRIDGE_NAME)
        wv.webViewClient = createWebViewClient()
        wv.webChromeClient = createWebChromeClient()
        wv.setDownloadListener { url, userAgent, contentDisposition, mimeType, contentLength ->
            onDownloadRequested(url, userAgent, contentDisposition, mimeType, contentLength)
        }

        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true)

        webView = wv
        binding.webContainer.addView(wv)

        val restored = restoredState
        if (restored != null) {
            restoredState = null
            wv.restoreState(restored)
        } else {
            wv.loadUrl(pendingInitialUrl ?: Config.START_URL)
        }
    }

    private fun destroyWebView() {
        val wv = webView ?: return
        webView = null
        (wv.parent as? ViewGroup)?.removeView(wv)
        wv.removeJavascriptInterface(JS_BRIDGE_NAME)
        wv.stopLoading()
        wv.webChromeClient = null
        wv.webViewClient = null
        wv.loadUrl("about:blank")
        wv.destroy()
    }

    private fun defaultUserAgent(): String {
        val base = WebSettings.getDefaultUserAgent(this)
        return if (Config.STRIP_WEBVIEW_UA_TOKEN) {
            base.replace("; wv", "").replace(" wv", "")
        } else {
            base
        }
    }

    private fun createWebViewClient(): WebViewClient = object : WebViewClient() {

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val uri = request.url
            if (Config.isInternal(uri)) return false
            return when (uri.scheme?.lowercase()) {
                "http", "https" -> {
                    openInBrowser(uri)
                    true
                }
                "tel", "mailto", "sms", "smsto", "intent", "market" -> {
                    openExternalIntent(uri)
                    true
                }
                else -> true
            }
        }

        override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
            binding.progressBar.visibility = View.VISIBLE
            hideOffline()
            if (Config.KEEP_SCREEN_ON_WHILE_LOADING) view.keepScreenOn = true
        }

        override fun onPageFinished(view: WebView, url: String?) {
            binding.progressBar.visibility = View.GONE
            binding.swipe.isRefreshing = false
            view.keepScreenOn = false
        }

        override fun onReceivedError(
            view: WebView,
            request: WebResourceRequest,
            error: WebResourceError,
        ) {
            if (!request.isForMainFrame) return
            Log.d(TAG, "Main frame error: ${error.errorCode} ${error.description}")
            binding.swipe.isRefreshing = false
            showOffline()
        }

        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            if (view !== webView) return true
            Log.w(TAG, "Renderer gone, didCrash=${detail.didCrash()}")
            toast(getString(R.string.renderer_crashed))
            destroyWebView()
            binding.root.post {
                if (!isFinishing && !isDestroyed) createWebView()
            }
            return true
        }
    }

    private fun createWebChromeClient(): WebChromeClient = object : WebChromeClient() {

        override fun onProgressChanged(view: WebView, newProgress: Int) {
            binding.progressBar.progress = newProgress
            binding.progressBar.visibility =
                if (newProgress in 1..99) View.VISIBLE else View.GONE
        }

        override fun onShowFileChooser(
            view: WebView,
            filePathCallback: ValueCallback<Array<Uri>>,
            fileChooserParams: FileChooserParams,
        ): Boolean {
            pendingFileChooser?.onReceiveValue(null)
            pendingFileChooser = filePathCallback
            pendingCameraUri = null
            val intent = buildFileChooserIntent(fileChooserParams) ?: run {
                pendingFileChooser = null
                filePathCallback.onReceiveValue(null)
                return false
            }
            return try {
                fileChooserLauncher.launch(intent)
                true
            } catch (e: ActivityNotFoundException) {
                Log.w(TAG, "No file chooser available: ${e.message}")
                pendingFileChooser = null
                pendingCameraUri = null
                filePathCallback.onReceiveValue(null)
                false
            }
        }

        override fun onPermissionRequest(request: PermissionRequest) {
            val wanted = mutableListOf<String>()
            if (request.resources.contains(PermissionRequest.RESOURCE_VIDEO_CAPTURE)) {
                wanted += Manifest.permission.CAMERA
            }
            if (request.resources.contains(PermissionRequest.RESOURCE_AUDIO_CAPTURE)) {
                wanted += Manifest.permission.RECORD_AUDIO
            }
            if (wanted.isEmpty()) {
                request.deny()
                return
            }
            val missing = wanted.filterNot { hasPermission(it) }
            if (missing.isEmpty()) {
                request.grant(wanted.toTypedArray())
            } else {
                pendingMediaRequest = request
                mediaPermissionLauncher.launch(missing.toTypedArray())
            }
        }

        override fun onGeolocationPermissionsShowPrompt(
            origin: String,
            callback: GeolocationPermissions.Callback,
        ) {
            val granted = hasPermission(Manifest.permission.ACCESS_FINE_LOCATION) ||
                hasPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
            if (granted) {
                callback.invoke(origin, true, false)
            } else {
                pendingGeoCallback = callback
                pendingGeoOrigin = origin
                locationPermissionLauncher.launch(
                    arrayOf(
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION,
                    ),
                )
            }
        }

        override fun onGeolocationPermissionsHidePrompt() {
            pendingGeoCallback?.invoke(pendingGeoOrigin, false, false)
            pendingGeoCallback = null
            pendingGeoOrigin = null
        }

        override fun onShowCustomView(view: View, callback: CustomViewCallback) {
            if (fullscreenView != null) {
                callback.onCustomViewHidden()
                return
            }
            fullscreenView = view
            fullscreenCallback = callback
            binding.fullscreenContainer.addView(
                view,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                ),
            )
            binding.fullscreenContainer.visibility = View.VISIBLE
            WindowInsetsControllerCompat(window, view).apply {
                systemBarsBehavior =
                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                hide(WindowInsetsCompat.Type.systemBars())
            }
        }

        override fun onHideCustomView() {
            hideFullscreen()
        }

        override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
            Log.d(
                TAG,
                "console: ${consoleMessage.message()} " +
                    "(${consoleMessage.sourceId()}:${consoleMessage.lineNumber()})",
            )
            return BuildConfig.DEBUG
        }
    }

    private fun buildFileChooserIntent(params: WebChromeClient.FileChooserParams): Intent? {
        val accepts = params.acceptTypes.filter { it.isNotBlank() }
        val imageOnly = accepts.isNotEmpty() && accepts.all { it.startsWith("image/") }

        val contentIntent = Intent(Intent.ACTION_GET_CONTENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = if (accepts.isEmpty()) "*/*" else accepts.joinToString(",")
            putExtra(
                Intent.EXTRA_MIME_TYPES,
                if (accepts.isEmpty()) arrayOf("*/*") else accepts.toTypedArray(),
            )
            if (params.mode == WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE) {
                putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            }
        }

        val initial = mutableListOf<Intent>()
        if (params.isCaptureEnabled && imageOnly) {
            createCameraIntent()?.let { initial += it }
        }

        val chooser = Intent.createChooser(contentIntent, getString(R.string.choose_file))
        if (initial.isNotEmpty()) {
            chooser.putExtra(Intent.EXTRA_INITIAL_INTENTS, initial.toTypedArray())
        }
        return chooser
    }

    private fun createCameraIntent(): Intent? {
        return try {
            val dir = File(cacheDir, "uploads").apply { mkdirs() }
            val file = File.createTempFile("capture_", ".jpg", dir)
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
            pendingCameraUri = uri
            Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
                putExtra(MediaStore.EXTRA_OUTPUT, uri)
                addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Camera intent unavailable: ${e.message}")
            null
        }
    }

    private fun onDownloadRequested(
        url: String,
        userAgent: String?,
        contentDisposition: String?,
        mimeType: String?,
        contentLength: Long,
    ) {
        val request = DownloadRequest(url, userAgent, contentDisposition, mimeType, contentLength)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            !hasPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        ) {
            pendingDownload = request
            storagePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        } else {
            startDownload(request)
        }
    }

    private fun startDownload(request: DownloadRequest) {
        DownloadHelper.enqueue(
            this,
            request.url,
            request.userAgent,
            request.contentDisposition,
            request.mimeType,
            request.contentLength,
        )
    }

    private fun showMenu() {
        val popup = PopupMenu(this, binding.menuBtn)
        popup.menuInflater.inflate(R.menu.main_menu, popup.menu)
        popup.menu.findItem(R.id.action_desktop).isChecked = desktopMode
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_refresh -> {
                    reloadPage()
                    true
                }
                R.id.action_home -> {
                    hideOffline()
                    loadUrl(Config.START_URL)
                    true
                }
                R.id.action_share -> {
                    shareCurrentPage()
                    true
                }
                R.id.action_open_in_browser -> {
                    webView?.url?.let { openInBrowser(Uri.parse(it)) }
                    true
                }
                R.id.action_desktop -> {
                    setDesktopMode(!desktopMode)
                    true
                }
                R.id.action_clear_data -> {
                    confirmClearData()
                    true
                }
                R.id.action_about -> {
                    showAboutDialog()
                    true
                }
                else -> false
            }
        }
        popup.show()
    }

    private fun shareCurrentPage() {
        val url = webView?.url ?: Config.START_URL
        val title = webView?.title
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, url)
            if (!title.isNullOrBlank()) putExtra(Intent.EXTRA_SUBJECT, title)
        }
        startActivity(Intent.createChooser(intent, getString(R.string.share_via)))
    }

    private fun setDesktopMode(enabled: Boolean) {
        desktopMode = enabled
        prefs().edit().putBoolean(PREF_DESKTOP_MODE, enabled).apply()
        webView?.settings?.userAgentString = if (enabled) desktopUserAgent else mobileUserAgent
        webView?.reload()
        toast(getString(if (enabled) R.string.desktop_mode_on else R.string.desktop_mode_off))
    }

    private fun showAboutDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_about, null)
        view.findViewById<TextView>(R.id.aboutVersionEl)?.text =
            getString(R.string.about_version, BuildConfig.VERSION_NAME)
        AlertDialog.Builder(this)
            .setView(view)
            .setPositiveButton(R.string.close, null)
            .show()
    }

    private fun confirmClearData() {
        AlertDialog.Builder(this)
            .setTitle(R.string.clear_data_title)
            .setMessage(R.string.clear_data_message)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.clear) { _, _ -> clearBrowsingData() }
            .show()
    }

    private fun clearBrowsingData() {
        CookieManager.getInstance().removeAllCookies(null)
        CookieManager.getInstance().flush()
        WebStorage.getInstance().deleteAllData()
        webView?.clearCache(true)
        webView?.clearHistory()
        webView?.clearFormData()
        toast(getString(R.string.data_cleared))
        webView?.reload()
    }

    private fun wireBackPressed() {
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (fullscreenView != null) {
                        hideFullscreen()
                        return
                    }
                    val wv = webView
                    if (wv != null && wv.canGoBack()) {
                        wv.goBack()
                        return
                    }
                    if (!Config.DOUBLE_BACK_TO_EXIT) {
                        finish()
                        return
                    }
                    val now = SystemClock.uptimeMillis()
                    if (now - lastBackPressAt < DOUBLE_BACK_INTERVAL_MS) {
                        finish()
                    } else {
                        lastBackPressAt = now
                        toast(getString(R.string.press_back_again))
                    }
                }
            },
        )
    }

    private fun hideFullscreen() {
        val view = fullscreenView ?: return
        WindowInsetsControllerCompat(window, binding.root)
            .show(WindowInsetsCompat.Type.systemBars())
        binding.fullscreenContainer.removeView(view)
        binding.fullscreenContainer.visibility = View.GONE
        fullscreenView = null
        fullscreenCallback?.onCustomViewHidden()
        fullscreenCallback = null
    }

    private fun retry() {
        if (!isOnline()) {
            showOffline()
            return
        }
        if (webView == null) {
            createWebView()
        } else {
            hideOffline()
            webView?.reload()
        }
    }

    private fun loadUrl(url: String) {
        pendingInitialUrl = url
        val wv = webView
        if (wv == null) retry() else wv.loadUrl(url)
    }

    private fun showOffline() {
        binding.offlineView.visibility = View.VISIBLE
        binding.progressBar.visibility = View.GONE
        binding.swipe.isRefreshing = false
        registerNetworkCallback()
    }

    private fun hideOffline() {
        if (binding.offlineView.visibility == View.VISIBLE) {
            binding.offlineView.visibility = View.GONE
        }
        unregisterNetworkCallback()
    }

    private fun registerNetworkCallback() {
        if (networkCallback != null) return
        val manager = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                runOnUiThread {
                    if (!isFinishing && !isDestroyed &&
                        binding.offlineView.visibility == View.VISIBLE
                    ) {
                        retry()
                    }
                }
            }
        }
        try {
            manager.registerDefaultNetworkCallback(callback)
            networkCallback = callback
        } catch (e: Exception) {
            Log.w(TAG, "Could not register network callback: ${e.message}")
        }
    }

    private fun unregisterNetworkCallback() {
        val callback = networkCallback ?: return
        networkCallback = null
        val manager = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        try {
            manager?.unregisterNetworkCallback(callback)
        } catch (e: Exception) {
            Log.w(TAG, "Could not unregister network callback: ${e.message}")
        }
    }

    private fun applyWindowInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.contentRoot) { view, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout(),
            )
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            view.updatePadding(
                left = bars.left,
                top = bars.top,
                right = bars.right,
                bottom = maxOf(bars.bottom, ime.bottom),
            )
            insets
        }
    }

    private fun isOnline(): Boolean {
        val manager = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return true
        val capabilities = manager.getNetworkCapabilities(manager.activeNetwork) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun openExternalIntent(uri: Uri) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, uri))
        } catch (e: ActivityNotFoundException) {
            toast(getString(R.string.no_app_found))
        }
    }

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun prefs() = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    companion object {
        private const val TAG = "MainActivity"
        private const val JS_BRIDGE_NAME = "Android"
        private const val PREFS_NAME = "app_prefs"
        private const val PREF_DESKTOP_MODE = "desktop_mode"
        private const val STATE_WEBVIEW = "webview_state"
        private const val DOUBLE_BACK_INTERVAL_MS = 2000L
    }
}
