package com.threejs.browser

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
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.animation.DecelerateInterpolator
import android.view.inputmethod.EditorInfo
import android.widget.LinearLayout
import android.widget.TextView
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
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.google.android.material.color.MaterialColors
import com.threejs.browser.databinding.ActivityMainBinding
import kotlin.math.abs
import kotlinx.coroutines.launch

private const val TAG = "MainActivity"
private const val BLANK_URL = "about:blank"
private const val PROGRESS_ALPHA = 80 // ~31% of 255
private const val TAB_ROW_MARGIN_DP = 4
private const val DRAWER_PADDING_DP = 8
// Match the chrome bar's URL-bubble offset (8 chrome padding + 6+24+14 icon column).
private const val DRAWER_HORIZONTAL_INSET_DP = 52
private const val SWIPE_ANIM_MS = 150L
private const val MAX_SWIPE_FADE = 0.7f

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val bridge = ThreeDevtoolsBridge()

    private lateinit var detectorScript: String
    private var docStartInjectionAvailable: Boolean = false
    private var progressAnimator: Animator? = null
    private var progressClip: ClipDrawable? = null
    private var imeWasOpen = false
    private val touchSlop by lazy { ViewConfiguration.get(this).scaledTouchSlop }

    private data class Tab(var url: String, var title: String? = null, var state: Bundle? = null)

    private val tabs = mutableListOf<Tab>()
    private var activeIndex = 0

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

        detectorScript = DetectorScripts.load(this)
        installBridge()
        configureWebView()
        observeDetectionState()
        wireUrlBar()
        installBackHandler()
        seedTabs()
        setupDragHandler()
        rebuildDrawer()

        if (savedInstanceState != null) {
            binding.webView.restoreState(savedInstanceState)
        } else {
            binding.webView.loadUrl(tabs[activeIndex].url)
            focusUrlBar()
        }
    }

    private fun seedTabs() {
        if (tabs.isNotEmpty()) return
        tabs += Tab(BLANK_URL)
        activeIndex = 0
    }

    private fun installBridge() {
        val webView = binding.webView

        docStartInjectionAvailable = WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
        if (docStartInjectionAvailable) {
            WebViewCompat.addDocumentStartJavaScript(webView, detectorScript, setOf("*"))
        }

        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            WebViewCompat.addWebMessageListener(webView, ThreeDevtoolsBridge.JS_OBJECT_NAME, setOf("*"), bridge)
        } else {
            @SuppressLint("AddJavascriptInterface")
            webView.addJavascriptInterface(bridge.Fallback(), ThreeDevtoolsBridge.JS_OBJECT_NAME)
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
        // Higher contrast than the chrome bar's colorSurface — darker in dark mode,
        // brighter (pure white) in light mode.
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
        // Don't scale page text with the system font size — match Chrome's default.
        settings.textZoom = 100
        settings.mediaPlaybackRequiresUserGesture = false
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        settings.setSupportZoom(true)
        settings.builtInZoomControls = true
        settings.displayZoomControls = false
        // Strip the WebView ";wv" UA token so sites don't serve a degraded experience.
        settings.userAgentString = settings.userAgentString
            .replace("; wv", "")
            .replace(" wv", "") + " ThreeBrowser/0.1"

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = false

            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                DetectionStore.reset()
                setUrlBubbleProgress(0, animate = false)
                if (!docStartInjectionAvailable) {
                    // Racy fallback: best-effort injection before page scripts run.
                    view.evaluateJavascript(detectorScript, null)
                }
                val tab = tabs.getOrNull(activeIndex)
                val isStateRestore = tab != null && url == tab.url
                if (tab != null && !isStateRestore) tab.title = null
                syncUrlBar(url)
            }

            override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
                syncUrlBar(url)
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
                if (tabs.isNotEmpty()) tabs[activeIndex].title = title
                if (!binding.urlBar.hasFocus()) setUrlBarText(currentTabDisplay())
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

    private fun wireUrlBar() {
        binding.urlBar.setOnEditorActionListener { v, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO) {
                loadUrl(v.text.toString())
                v.clearFocus()
                hideKeyboard(v)
                animateDrawerTo(0)
                true
            } else false
        }
        binding.urlBar.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                val url = tabs.getOrNull(activeIndex)?.url.orEmpty()
                binding.urlBar.setText(if (url == BLANK_URL) "" else url)
                binding.urlBar.selectAll()
            } else {
                setUrlBarText(currentTabDisplay())
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

    private fun hideKeyboard(view: View) {
        ViewCompat.getWindowInsetsController(view)?.hide(WindowInsetsCompat.Type.ime())
    }

    private fun showKeyboard(view: View) {
        ViewCompat.getWindowInsetsController(view)?.show(WindowInsetsCompat.Type.ime())
    }

    private fun setUrlBarText(text: String) {
        if (binding.urlBar.text?.toString() != text) binding.urlBar.setText(text)
    }

    private fun syncUrlBar(url: String?) {
        val safe = url.orEmpty()
        if (tabs.isNotEmpty() && safe.isNotEmpty()) tabs[activeIndex].url = safe
        if (!binding.urlBar.hasFocus()) setUrlBarText(currentTabDisplay())
    }

    private fun currentTabDisplay(): String =
        tabLabel(tabs.getOrNull(activeIndex))

    private fun tabLabel(tab: Tab?): String {
        if (tab == null) return getString(R.string.loading)
        val title = tab.title?.takeIf { it.isNotBlank() && it != BLANK_URL }
        if (title != null) return title
        if (tab.url.isEmpty() || tab.url == BLANK_URL) return getString(R.string.new_tab)
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
            startHeight = binding.tabsDrawer.layoutParams.height
            drawerAnimator?.cancel()
        }
        // Y axis grows downward → up-drag has negative dy → drawer grows.
        val onMove: (Float) -> Unit = { dy ->
            setDrawerHeight((startHeight - dy.toInt()).coerceAtLeast(0))
        }
        val onEnd: (Float) -> Unit = { /* stay where released */ }
        listOf(binding.chromeContainer, binding.tabsDrawer).forEach { container ->
            container.onDragStart = onStart
            container.onDragMove = onMove
            container.onDragEnd = onEnd
        }
    }

    private var drawerAnimator: Animator? = null

    private fun setDrawerHeight(h: Int) {
        val drawer = binding.tabsDrawer
        val params = drawer.layoutParams
        if (params.height == h) return
        params.height = h
        drawer.layoutParams = params
    }

    private fun animateDrawerTo(target: Int) {
        drawerAnimator?.cancel()
        val current = binding.tabsDrawer.layoutParams.height
        if (current == target) return
        drawerAnimator = ValueAnimator.ofInt(current, target).apply {
            duration = 200L
            interpolator = DecelerateInterpolator()
            addUpdateListener { setDrawerHeight(it.animatedValue as Int) }
            start()
        }
    }

    private fun rebuildDrawer() {
        val drawer = binding.tabsDrawer
        drawer.removeAllViews()
        val padH = dpToPx(DRAWER_HORIZONTAL_INSET_DP)
        val padV = dpToPx(DRAWER_PADDING_DP)
        drawer.setPadding(padH, padV, padH, padV)
        tabs.forEachIndexed { index, tab ->
            if (index == activeIndex) return@forEachIndexed
            val row = makeTabRow(tabLabel(tab)) { switchToTab(index) }
            attachSwipeToDelete(row) { closeTab(tab) }
            drawer.addView(row)
        }
        drawer.addView(makeTabRow("+") { openBlankTab() })
    }

    private fun makeTabRow(label: CharSequence, onClick: () -> Unit): TextView {
        val tv = TextView(this).apply {
            text = label
            background = AppCompatResources.getDrawable(context, R.drawable.tab_bubble_bg)
            gravity = Gravity.CENTER
            isSingleLine = true
            ellipsize = android.text.TextUtils.TruncateAt.END
            textSize = 15f
            setPadding(dpToPx(14), dpToPx(6), dpToPx(14), dpToPx(8))
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dpToPx(TAB_ROW_MARGIN_DP)
                bottomMargin = dpToPx(TAB_ROW_MARGIN_DP)
            }
        }
        return tv
    }

    private fun openBlankTab() {
        tabs += Tab(BLANK_URL)
        switchToTab(tabs.size - 1)
        focusUrlBar()
    }

    private fun closeTab(tab: Tab) {
        val index = tabs.indexOfFirst { it === tab }
        if (index < 0 || index == activeIndex) return
        tabs.removeAt(index)
        if (index < activeIndex) activeIndex--
        rebuildDrawer()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun attachSwipeToDelete(view: View, onDelete: () -> Unit) {
        var downX = 0f
        var swiping = false
        view.setOnTouchListener { v, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = ev.rawX
                    swiping = false
                    false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = ev.rawX - downX
                    if (!swiping && abs(dx) > touchSlop) {
                        swiping = true
                        v.parent.requestDisallowInterceptTouchEvent(true)
                    }
                    if (swiping) {
                        v.translationX = dx
                        v.alpha = swipeAlpha(dx, v.width)
                        true
                    } else false
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (!swiping) return@setOnTouchListener false
                    val dx = v.translationX
                    if (abs(dx) > v.width / 2f) {
                        val target = if (dx > 0) v.width * 2f else -v.width * 2f
                        v.animate().translationX(target).alpha(0f).setDuration(SWIPE_ANIM_MS)
                            .withEndAction { onDelete() }.start()
                    } else {
                        v.animate().translationX(0f).alpha(1f).setDuration(SWIPE_ANIM_MS).start()
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun swipeAlpha(dx: Float, width: Int): Float {
        if (width <= 0) return 1f
        return 1f - (abs(dx) / width).coerceIn(0f, MAX_SWIPE_FADE)
    }

    private fun focusUrlBar() {
        binding.urlBar.requestFocus()
        binding.urlBar.post { showKeyboard(binding.urlBar) }
    }

    private fun switchToTab(index: Int) {
        if (index == activeIndex || index !in tabs.indices) return
        tabs[activeIndex].state = Bundle().also { binding.webView.saveState(it) }
        activeIndex = index
        // Detection / progress reset come for free via the onPageStarted that follows.
        val incoming = tabs[index]
        incoming.state?.let { binding.webView.restoreState(it) }
            ?: binding.webView.loadUrl(incoming.url)
        if (!binding.urlBar.hasFocus()) setUrlBarText(currentTabDisplay())
        rebuildDrawer()
        animateDrawerTo(0)
    }

    private fun dpToPx(dp: Int): Int = (dp * resources.displayMetrics.density).toInt()
}
