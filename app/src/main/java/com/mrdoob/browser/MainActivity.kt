package com.mrdoob.browser

import android.animation.Animator
import android.animation.AnimatorSet
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Color
import android.graphics.Bitmap
import android.graphics.Rect
import android.graphics.drawable.ClipDrawable
import android.graphics.drawable.LayerDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.util.Patterns
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.view.inputmethod.EditorInfo
import android.webkit.ConsoleMessage
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.mrdoob.browser.databinding.ActivityMainBinding
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

private const val TAG = "MainActivity"
private const val BLANK_URL = "about:blank"
private const val PROGRESS_ALPHA = 80 // ~31% of 255
private const val PREF_LAST_URL = "last_url"
private val SHARE_URL_REGEX = Regex("""https?://\S+""")

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var panel: PanelRenderer

    private val extensionRouter = ExtensionRouter()
    private val extensionBridge = ExtensionBridge(
        router = extensionRouter,
        onOrientationLock = { type -> applyOrientationLock(type) },
        onConsoleEntry = { level, segmentsJson ->
            ConsoleLog.add(ConsoleLog.Entry(
                level = consoleLevelFromName(level),
                segmentsJson = segmentsJson
            ))
        }
    )
    private val panelExtensionBridge = PanelExtensionBridge(extensionRouter)
    private val networkBridge = NetworkBridge { rid, dataUrl -> panel.deliverBlob(rid, dataUrl) }
    private val panelBridge = PanelBridge(
        onFetchBlob = { rid -> fetchBlobFromMain(rid) },
        onEval = { evalId, source -> evalInPage(evalId, source) }
    )

    private lateinit var documentStartScript: String
    private var docStartInjectionAvailable: Boolean = false
    private var progressAnimator: Animator? = null
    private var progressClip: ClipDrawable? = null
    private var imeWasOpen = false

    private var currentUrl: String = BLANK_URL
    private var currentTitle: String? = null
    private var currentTab = PanelRenderer.Tab.CONSOLE
    private var sourceDirty = true

    private var fullscreenView: View? = null
    private var fullscreenCallback: WebChromeClient.CustomViewCallback? = null
    private var preFullscreenOrientation: Int = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        progressClip = (binding.urlBubble.background as? LayerDrawable)
            ?.findDrawableByLayerId(android.R.id.progress) as? ClipDrawable

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val sys = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            val imeOpen = ime.bottom > 0
            if (imeWasOpen && !imeOpen && binding.urlBar.hasFocus()) {
                binding.urlBar.clearFocus()
            }
            imeWasOpen = imeOpen
            v.setPadding(sys.left, sys.top, sys.right, maxOf(sys.bottom, ime.bottom))
            WindowInsetsCompat.CONSUMED
        }

        documentStartScript = DocumentStartScripts.load(this)
        extensionRouter.bindPageView(binding.webView)
        extensionRouter.bindPanelView(binding.threePanelView)
        installBridges()
        configureWebView()
        panel = PanelRenderer(this, binding.panelView, binding.webView).also { it.init() }
        setupThreeJsPanel(this, binding.threePanelView, panelExtensionBridge)
        observeDetectionState()
        observeNetworkLog()
        observeConsoleLog()
        wireUrlBar()
        wirePanelHeader()
        installBackHandler()
        setupDragHandler()
        updateTabStyles()
        // Reserve the edge-adjacent chrome buttons from Android's back-gesture region,
        // otherwise a tap near the screen edge gets swallowed as a back swipe.
        excludeFromSystemGestures(binding.panelReload)
        excludeFromSystemGestures(binding.threeLogoButton)

        if (savedInstanceState != null) {
            binding.webView.restoreState(savedInstanceState)
        } else {
            val incoming = extractUrlFromIntent(intent) ?: lastSessionUrl()
            if (incoming != null) {
                loadUrl(incoming)
            } else {
                binding.webView.loadUrl(BLANK_URL)
                focusUrlBar()
            }
        }
    }

    override fun onPause() {
        super.onPause()
        val url = currentUrl.takeIf { it.isNotEmpty() && it != BLANK_URL }
        getPreferences(MODE_PRIVATE).edit().putString(PREF_LAST_URL, url).apply()
    }

    override fun onDestroy() {
        // onRenderProcessGone → recreate() and process death mid-fullscreen leave the
        // custom view attached to decorView; clean up so we don't leak.
        if (fullscreenView != null) exitFullscreen()
        super.onDestroy()
    }

    private fun lastSessionUrl(): String? = getPreferences(MODE_PRIVATE)
        .getString(PREF_LAST_URL, null)
        ?.takeIf { it.isNotEmpty() && it != BLANK_URL }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        extractUrlFromIntent(intent)?.let { loadUrl(it) }
    }

    private fun extractUrlFromIntent(intent: Intent?): String? {
        intent ?: return null
        return when (intent.action) {
            Intent.ACTION_VIEW -> intent.dataString
            Intent.ACTION_SEND -> {
                val text = intent.getStringExtra(Intent.EXTRA_TEXT)?.trim().orEmpty()
                if (text.isEmpty()) null
                // If the share is a "Check this out: https://..." style blob,
                // pull the first URL out — otherwise feed the raw text to loadUrl
                // (which routes plain text to search).
                else SHARE_URL_REGEX.find(text)?.value ?: text
            }
            else -> null
        }
    }

    private fun installBridges() {
        val webView = binding.webView

        docStartInjectionAvailable = WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
        if (docStartInjectionAvailable) {
            WebViewCompat.addDocumentStartJavaScript(webView, documentStartScript, setOf("*"))
        }

        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            WebViewCompat.addWebMessageListener(webView, ExtensionBridge.JS_OBJECT_NAME, setOf("*"), extensionBridge)
            WebViewCompat.addWebMessageListener(webView, NetworkBridge.JS_OBJECT_NAME, setOf("*"), networkBridge)
            WebViewCompat.addWebMessageListener(binding.panelView, PanelBridge.JS_OBJECT_NAME, setOf("*"), panelBridge)
        } else {
            @SuppressLint("AddJavascriptInterface")
            run {
                webView.addJavascriptInterface(extensionBridge.Fallback(), ExtensionBridge.JS_OBJECT_NAME)
                webView.addJavascriptInterface(networkBridge.Fallback(), NetworkBridge.JS_OBJECT_NAME)
                binding.panelView.addJavascriptInterface(panelBridge.Fallback(), PanelBridge.JS_OBJECT_NAME)
            }
        }

        if (!docStartInjectionAvailable) {
            Toast.makeText(
                this,
                "Old WebView — detection may be unreliable. Update Android System WebView from Play Store.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebView() {
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)

        val webView = binding.webView
        webView.setBackgroundColor(
            webView.materialColor(com.google.android.material.R.attr.colorSurfaceContainerLowest)
        )
        val settings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        @Suppress("DEPRECATION")
        settings.databaseEnabled = true
        settings.loadWithOverviewMode = true
        settings.useWideViewPort = true
        settings.textZoom = 100
        settings.mediaPlaybackRequiresUserGesture = false
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
            // WebView checks system night mode itself; pages with their own dark theme are left alone.
            WebSettingsCompat.setAlgorithmicDarkeningAllowed(settings, true)
        }
        settings.setSupportZoom(true)
        settings.builtInZoomControls = true
        settings.displayZoomControls = false
        settings.userAgentString = settings.userAgentString
            .replace("; wv", "")
            .replace(" wv", "") + " Netex/0.1"

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = false

            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                DetectionStore.reset()
                NetworkLog.reset()
                ConsoleLog.reset()
                extensionRouter.onPageNavigationStarted()
                setUrlBubbleProgress(0, animate = false)
                if (!docStartInjectionAvailable) {
                    // Racy fallback: best-effort injection before page scripts run.
                    view.evaluateJavascript(documentStartScript, null)
                }
                if (url != null && url != currentUrl) currentTitle = null
                syncUrlBar(url)
            }

            override fun onPageFinished(view: WebView, url: String?) {
                sourceDirty = true
                maybeRefreshSource()
            }

            override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
                syncUrlBar(url)
                // SPA route changes (history.pushState) skip onPageFinished — keep source honest.
                sourceDirty = true
                maybeRefreshSource()
            }

            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail?): Boolean {
                Log.w(TAG, "WebView render process gone, recreating activity")
                recreate()
                return true
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                setUrlBubbleProgress(newProgress)
            }

            override fun onReceivedTitle(view: WebView, title: String?) {
                currentTitle = title
                if (!binding.urlBar.hasFocus()) setUrlBarText(currentDisplay())
            }

            override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                val segments = JSONArray().put(
                    JSONObject().put("text", message.message() ?: "").put("style", "")
                )
                ConsoleLog.add(ConsoleLog.Entry(
                    level = consoleLevelOf(message.messageLevel()),
                    segmentsJson = segments.toString(),
                    sourceId = message.sourceId()?.takeIf { it.isNotEmpty() },
                    lineNumber = message.lineNumber()
                ))
                return true
            }

            override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                enterFullscreen(view, callback)
            }

            override fun onHideCustomView() {
                exitFullscreen()
            }
        }
    }

    private fun enterFullscreen(view: View, callback: WebChromeClient.CustomViewCallback) {
        if (fullscreenView != null) {
            callback.onCustomViewHidden()
            return
        }
        fullscreenView = view
        fullscreenCallback = callback
        preFullscreenOrientation = requestedOrientation
        binding.root.visibility = View.GONE
        (window.decorView as ViewGroup).addView(
            view,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )
        WindowInsetsControllerCompat(window, view).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    private fun exitFullscreen() {
        val view = fullscreenView ?: return
        (window.decorView as ViewGroup).removeView(view)
        binding.root.visibility = View.VISIBLE
        fullscreenView = null
        fullscreenCallback?.onCustomViewHidden()
        fullscreenCallback = null
        setRequestedOrientationIfChanged(preFullscreenOrientation)
        WindowInsetsControllerCompat(window, window.decorView)
            .show(WindowInsetsCompat.Type.systemBars())
    }

    // Per the Screen Orientation API, lock() is only valid in fullscreen — rotating the
    // whole browser otherwise would be hostile.
    private fun applyOrientationLock(type: String?) {
        if (fullscreenView == null) return
        val target = when (type) {
            null -> preFullscreenOrientation
            "landscape" -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            "landscape-primary" -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            "landscape-secondary" -> ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE
            "portrait" -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            "portrait-primary" -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            "portrait-secondary" -> ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT
            "any" -> ActivityInfo.SCREEN_ORIENTATION_FULL_USER
            "natural" -> ActivityInfo.SCREEN_ORIENTATION_USER
            else -> return
        }
        setRequestedOrientationIfChanged(target)
    }

    // setRequestedOrientation is not a no-op when the value is unchanged — it round-trips
    // through ActivityClient. Pages can spam lock() during transitions; gate the write.
    private fun setRequestedOrientationIfChanged(value: Int) {
        if (requestedOrientation != value) requestedOrientation = value
    }

    private fun observeDetectionState() {
        var appliedTint: Int? = null
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                DetectionStore.flow.collect { state ->
                    when (state) {
                        is DetectionState.NotDetected -> {
                            binding.threeLogo.alpha = 0.3f
                            binding.revisionBadge.visibility = View.INVISIBLE
                        }
                        is DetectionState.Detected -> {
                            binding.threeLogo.alpha = 1.0f
                            binding.revisionBadge.text = state.badgeText
                            binding.revisionBadge.visibility = View.VISIBLE
                            val tint = state.backgroundColor?.let(::parseHexColor)
                                ?: binding.revisionBadge.materialColor(com.google.android.material.R.attr.colorPrimary)
                            if (tint != appliedTint) {
                                binding.revisionBadge.background?.mutate()?.setTint(tint)
                                appliedTint = tint
                            }
                        }
                    }
                }
            }
        }
    }

    private fun observeNetworkLog() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                NetworkLog.events.collect { event ->
                    when (event) {
                        is NetworkLog.Event.Added -> {
                            val displayUrl = UrlDisplay.shorten(event.record.url, currentUrl)
                            panel.appendNetworkRow(event.record, displayUrl)
                        }
                        NetworkLog.Event.Cleared -> panel.clearNetwork()
                    }
                }
            }
        }
    }

    private fun observeConsoleLog() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                ConsoleLog.events.collect { event ->
                    when (event) {
                        is ConsoleLog.Event.Added -> panel.appendConsoleEntry(event.entry)
                        ConsoleLog.Event.Cleared -> panel.clearConsole()
                    }
                }
            }
        }
    }

    private fun consoleLevelOf(level: ConsoleMessage.MessageLevel?): ConsoleLog.Level = when (level) {
        ConsoleMessage.MessageLevel.WARNING -> ConsoleLog.Level.WARN
        ConsoleMessage.MessageLevel.ERROR -> ConsoleLog.Level.ERROR
        ConsoleMessage.MessageLevel.DEBUG -> ConsoleLog.Level.DEBUG
        else -> ConsoleLog.Level.LOG
    }

    private fun consoleLevelFromName(name: String): ConsoleLog.Level = when (name.lowercase()) {
        "warn", "warning" -> ConsoleLog.Level.WARN
        "error" -> ConsoleLog.Level.ERROR
        "debug" -> ConsoleLog.Level.DEBUG
        "info" -> ConsoleLog.Level.INFO
        else -> ConsoleLog.Level.LOG
    }

    private fun wireUrlBar() {
        binding.urlBar.setOnEditorActionListener { v, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO) {
                loadUrl(v.text.toString())
                v.clearFocus()
                hideKeyboard(v)
                true
            } else false
        }
        binding.urlBar.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                binding.urlBar.setText(if (currentUrl == BLANK_URL) "" else currentUrl)
                // Wait until ACTION_UP's cursor placement settles, otherwise it clears the selection.
                binding.urlBar.post { binding.urlBar.selectAll() }
            } else {
                setUrlBarText(currentDisplay())
            }
        }
        val dismissUrlEditing = View.OnClickListener {
            if (binding.urlBar.hasFocus()) {
                binding.urlBar.clearFocus()
                hideKeyboard(binding.urlBar)
            }
        }
        binding.threeLogoButton.setOnClickListener(dismissUrlEditing)
    }

    private fun wirePanelHeader() {
        binding.tabConsole.setOnClickListener { switchTab(PanelRenderer.Tab.CONSOLE) }
        binding.tabSource.setOnClickListener { switchTab(PanelRenderer.Tab.SOURCE) }
        binding.tabNetwork.setOnClickListener { switchTab(PanelRenderer.Tab.NETWORK) }
        binding.tabThreejs.setOnClickListener { switchTab(PanelRenderer.Tab.THREEJS) }
        binding.panelReload.setOnClickListener { binding.webView.reload() }
    }

    private fun excludeFromSystemGestures(view: View) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        view.addOnLayoutChangeListener { v, l, t, r, b, _, _, _, _ ->
            v.systemGestureExclusionRects = listOf(Rect(0, 0, r - l, b - t))
        }
    }

    private fun switchTab(tab: PanelRenderer.Tab) {
        if (tab == currentTab) return
        currentTab = tab
        updateTabStyles()
        val showThreeJs = tab == PanelRenderer.Tab.THREEJS
        binding.panelView.visibility = if (showThreeJs) View.GONE else View.VISIBLE
        binding.threePanelView.visibility = if (showThreeJs) View.VISIBLE else View.GONE
        if (!showThreeJs) panel.setTab(tab)
        if (tab == PanelRenderer.Tab.SOURCE) maybeRefreshSource()
    }

    private fun updateTabStyles() {
        val active = binding.root.materialColor(com.google.android.material.R.attr.colorOnSurface)
        val inactive = binding.root.materialColor(com.google.android.material.R.attr.colorOnSurfaceVariant)
        binding.tabConsole.setTextColor(if (currentTab == PanelRenderer.Tab.CONSOLE) active else inactive)
        binding.tabSource.setTextColor(if (currentTab == PanelRenderer.Tab.SOURCE) active else inactive)
        binding.tabNetwork.setTextColor(if (currentTab == PanelRenderer.Tab.NETWORK) active else inactive)
        binding.tabThreejs.setTextColor(if (currentTab == PanelRenderer.Tab.THREEJS) active else inactive)
    }

    private fun hideKeyboard(view: View) {
        ViewCompat.getWindowInsetsController(view)?.hide(WindowInsetsCompat.Type.ime())
    }

    private fun showKeyboard(view: View) {
        ViewCompat.getWindowInsetsController(view)?.show(WindowInsetsCompat.Type.ime())
    }

    private fun focusUrlBar() {
        binding.urlBar.requestFocus()
        binding.urlBar.post { showKeyboard(binding.urlBar) }
    }

    private fun setUrlBarText(text: String) {
        if (binding.urlBar.text?.toString() != text) binding.urlBar.setText(text)
    }

    private fun syncUrlBar(url: String?) {
        val safe = url.orEmpty()
        if (safe.isNotEmpty()) currentUrl = safe
        if (!binding.urlBar.hasFocus()) setUrlBarText(currentDisplay())
    }

    private fun currentDisplay(): String {
        currentTitle?.takeIf { it.isNotBlank() && it != BLANK_URL }?.let { return it }
        if (currentUrl.isEmpty() || currentUrl == BLANK_URL) return getString(R.string.new_tab)
        return getString(R.string.loading)
    }

    private fun setUrlBubbleProgress(percent: Int, animate: Boolean = true) {
        val clip = progressClip ?: return

        progressAnimator?.cancel()
        progressAnimator = null

        val target = percent.coerceIn(0, 100) * 100

        if (!animate || target < clip.level) {
            clip.alpha = PROGRESS_ALPHA
            clip.level = target
            return
        }

        clip.alpha = PROGRESS_ALPHA
        progressAnimator = if (percent >= 100) {
            val fill = ValueAnimator.ofInt(clip.level, 10000).apply {
                duration = 200L
                addUpdateListener { clip.level = it.animatedValue as Int }
            }
            val fade = ValueAnimator.ofInt(PROGRESS_ALPHA, 0).apply {
                duration = 250L
                startDelay = 80L
                addUpdateListener { clip.alpha = it.animatedValue as Int }
            }
            AnimatorSet().apply {
                playSequentially(fill, fade)
                start()
            }
        } else {
            ValueAnimator.ofInt(clip.level, target).apply {
                duration = 300L
                interpolator = DecelerateInterpolator()
                addUpdateListener { clip.level = it.animatedValue as Int }
                start()
            }
        }
    }

    private fun loadUrl(input: String) {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return
        val normalized = when {
            trimmed.startsWith("http://") || trimmed.startsWith("https://") -> trimmed
            trimmed.startsWith("about:") || trimmed.startsWith("file:") -> trimmed
            looksLikeUrl(trimmed) -> "https://$trimmed"
            else -> "https://duckduckgo.com/?q=${Uri.encode("!ducky $trimmed")}"
        }
        binding.webView.loadUrl(normalized)
    }

    private fun looksLikeUrl(input: String): Boolean {
        if (input.contains(' ')) return false
        val host = Uri.parse("https://$input").host?.takeIf { it.isNotEmpty() } ?: return false
        if (host.equals("localhost", ignoreCase = true)) return true
        if (Patterns.IP_ADDRESS.matcher(host).matches()) return true
        val tld = host.substringAfterLast('.', "").lowercase()
        return tld in knownTlds(this)
    }

    companion object {
        // Bundled IANA TLD list; file extensions like ".js" / ".py" fall through to search.
        private var cachedTlds: Set<String>? = null

        private fun knownTlds(context: Context): Set<String> = cachedTlds ?: context
            .resources.openRawResource(R.raw.tlds)
            .bufferedReader().useLines { it.filter(String::isNotBlank).toSet() }
            .also { cachedTlds = it }
    }

    private fun installBackHandler() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (fullscreenView != null) {
                    exitFullscreen()
                } else if (binding.webView.canGoBack()) {
                    binding.webView.goBack()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        binding.webView.saveState(outState)
    }

    private fun setupDragHandler() {
        var startHeight = 0
        val onStart: () -> Unit = {
            startHeight = binding.bottomPanel.layoutParams.height
        }
        // Y axis grows downward → up-drag has negative dy → panel grows.
        val onMove: (Float) -> Unit = { dy ->
            setPanelHeight((startHeight - dy.toInt()).coerceAtLeast(0))
        }
        val onEnd: (Float) -> Unit = { /* stay where released */ }
        binding.chromeContainer.onDragStart = onStart
        binding.chromeContainer.onDragMove = onMove
        binding.chromeContainer.onDragEnd = onEnd
    }

    private fun setPanelHeight(h: Int) {
        val p = binding.bottomPanel
        val params = p.layoutParams
        if (params.height == h) return
        val wasClosed = params.height == 0
        params.height = h
        p.layoutParams = params
        if (wasClosed && h > 0 && currentTab == PanelRenderer.Tab.SOURCE) maybeRefreshSource()
    }

    private fun maybeRefreshSource() {
        if (!sourceDirty) return
        if (currentTab != PanelRenderer.Tab.SOURCE) return
        if (binding.bottomPanel.layoutParams.height <= 0) return
        sourceDirty = false
        panel.refreshSource()
    }

    private fun fetchBlobFromMain(rid: String) {
        binding.webView.evaluateJavascript(
            "window.__netex && window.__netex.findBlob(${JSONObject.quote(rid)})",
            null
        )
    }

    private fun evalInPage(evalId: String, source: String) {
        val js = "(window.__netex && window.__netex.consoleEval) " +
            "? window.__netex.consoleEval(${JSONObject.quote(source)}) " +
            ": [false,'console eval not ready']"
        binding.webView.evaluateJavascript(js) { result ->
            panel.deliverEvalResult(evalId, result ?: "null")
        }
    }

    private fun parseHexColor(hex: String): Int? = try { Color.parseColor(hex) } catch (_: IllegalArgumentException) { null }
}
