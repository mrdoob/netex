package com.mrdoob.browser

import android.animation.Animator
import android.animation.AnimatorSet
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.ClipDrawable
import android.graphics.drawable.LayerDrawable
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.util.Patterns
import android.view.View
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.google.android.material.color.MaterialColors
import com.mrdoob.browser.databinding.ActivityMainBinding
import kotlinx.coroutines.launch
import org.json.JSONObject

private const val TAG = "MainActivity"
private const val BLANK_URL = "about:blank"
private const val PROGRESS_ALPHA = 80 // ~31% of 255

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var panel: PanelRenderer

    private val threeBridge = ThreeDevtoolsBridge()
    private val networkBridge = NetworkBridge { rid, dataUrl -> panel.deliverBlob(rid, dataUrl) }
    private val panelBridge = PanelBridge { rid -> fetchBlobFromMain(rid) }

    private lateinit var documentStartScript: String
    private var docStartInjectionAvailable: Boolean = false
    private var progressAnimator: Animator? = null
    private var progressClip: ClipDrawable? = null
    private var imeWasOpen = false
    private var panelAnimator: Animator? = null

    private var currentUrl: String = BLANK_URL
    private var currentTitle: String? = null
    private var currentTab = PanelRenderer.Tab.SOURCE
    private var sourceDirty = true

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
        installBridges()
        configureWebView()
        panel = PanelRenderer(this, binding.panelView, binding.webView).also { it.init() }
        observeDetectionState()
        observeNetworkLog()
        wireUrlBar()
        wirePanelHeader()
        installBackHandler()
        setupDragHandler()
        updateTabStyles()

        if (savedInstanceState != null) {
            binding.webView.restoreState(savedInstanceState)
        } else {
            binding.webView.loadUrl(BLANK_URL)
            focusUrlBar()
        }
    }

    private fun installBridges() {
        val webView = binding.webView

        docStartInjectionAvailable = WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
        if (docStartInjectionAvailable) {
            WebViewCompat.addDocumentStartJavaScript(webView, documentStartScript, setOf("*"))
        }

        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            WebViewCompat.addWebMessageListener(webView, ThreeDevtoolsBridge.JS_OBJECT_NAME, setOf("*"), threeBridge)
            WebViewCompat.addWebMessageListener(webView, NetworkBridge.JS_OBJECT_NAME, setOf("*"), networkBridge)
            WebViewCompat.addWebMessageListener(binding.panelView, PanelBridge.JS_OBJECT_NAME, setOf("*"), panelBridge)
        } else {
            @SuppressLint("AddJavascriptInterface")
            run {
                webView.addJavascriptInterface(threeBridge.Fallback(), ThreeDevtoolsBridge.JS_OBJECT_NAME)
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
            MaterialColors.getColor(webView, com.google.android.material.R.attr.colorSurfaceContainerLowest)
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
        settings.setSupportZoom(true)
        settings.builtInZoomControls = true
        settings.displayZoomControls = false
        settings.userAgentString = settings.userAgentString
            .replace("; wv", "")
            .replace(" wv", "") + " ThreeBrowser/0.1"

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = false

            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                DetectionStore.reset()
                NetworkLog.reset()
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
                if (BuildConfig.DEBUG) {
                    Log.d("WebViewConsole", "${message.messageLevel()} ${message.message()} (${message.sourceId()}:${message.lineNumber()})")
                }
                return true
            }
        }
    }

    private fun observeDetectionState() {
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
                            binding.revisionBadge.text = state.revision.filter { it.isDigit() }
                            binding.revisionBadge.visibility = View.VISIBLE
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

    private fun wireUrlBar() {
        binding.urlBar.setOnEditorActionListener { v, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO) {
                loadUrl(v.text.toString())
                v.clearFocus()
                hideKeyboard(v)
                animatePanelTo(0)
                true
            } else false
        }
        binding.urlBar.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                binding.urlBar.setText(if (currentUrl == BLANK_URL) "" else currentUrl)
                binding.urlBar.selectAll()
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
        binding.settingsButton.setOnClickListener(dismissUrlEditing)
    }

    private fun wirePanelHeader() {
        binding.tabSource.setOnClickListener { switchTab(PanelRenderer.Tab.SOURCE) }
        binding.tabNetwork.setOnClickListener { switchTab(PanelRenderer.Tab.NETWORK) }
        binding.panelReload.setOnClickListener { binding.webView.reload() }
    }

    private fun switchTab(tab: PanelRenderer.Tab) {
        if (tab == currentTab) return
        currentTab = tab
        updateTabStyles()
        panel.setTab(tab)
        if (tab == PanelRenderer.Tab.SOURCE) maybeRefreshSource()
    }

    private fun updateTabStyles() {
        val active = MaterialColors.getColor(binding.root, com.google.android.material.R.attr.colorOnSurface)
        val inactive = MaterialColors.getColor(binding.root, com.google.android.material.R.attr.colorOnSurfaceVariant)
        binding.tabSource.setTextColor(if (currentTab == PanelRenderer.Tab.SOURCE) active else inactive)
        binding.tabNetwork.setTextColor(if (currentTab == PanelRenderer.Tab.NETWORK) active else inactive)
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
                if (binding.webView.canGoBack()) {
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
            panelAnimator?.cancel()
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

    private fun animatePanelTo(target: Int) {
        panelAnimator?.cancel()
        val current = binding.bottomPanel.layoutParams.height
        if (current == target) return
        panelAnimator = ValueAnimator.ofInt(current, target).apply {
            duration = 200L
            interpolator = DecelerateInterpolator()
            addUpdateListener { setPanelHeight(it.animatedValue as Int) }
            start()
        }
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
            "window.__threeBrowser && window.__threeBrowser.findBlob(${JSONObject.quote(rid)})",
            null
        )
    }
}
