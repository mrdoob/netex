package com.threejs.browser

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.util.Log
import android.view.View
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
import com.threejs.browser.databinding.ActivityMainBinding
import kotlinx.coroutines.launch

private const val TAG = "MainActivity"
private const val DEFAULT_URL = "https://threejs.org/examples/"

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val bridge = ThreeDevtoolsBridge()

    private lateinit var detectorScript: String
    private var docStartInjectionAvailable: Boolean = false
    private var currentFullUrl: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val sys = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            // Skip the bottom system-bar inset so the WebView extends under the gesture handle.
            v.setPadding(sys.left, sys.top, sys.right, ime.bottom)
            WindowInsetsCompat.CONSUMED
        }

        detectorScript = DetectorScripts.load(this)
        installBridge()
        configureWebView()
        observeDetectionState()
        wireUrlBar()
        installBackHandler()

        if (savedInstanceState != null) {
            binding.webView.restoreState(savedInstanceState)
        } else {
            loadUrl(DEFAULT_URL)
        }
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
                if (!docStartInjectionAvailable) {
                    // Racy fallback: best-effort injection before page scripts run.
                    view.evaluateJavascript(detectorScript, null)
                }
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
                val bar = binding.progressBar
                if (newProgress in 1..99) {
                    bar.visibility = View.VISIBLE
                    bar.progress = newProgress
                } else {
                    bar.visibility = View.GONE
                }
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
                true
            } else false
        }
        binding.urlBar.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                binding.urlBar.setText(currentFullUrl)
                binding.urlBar.selectAll()
            } else {
                binding.urlBar.setText(abbreviateUrl(currentFullUrl))
            }
        }
        binding.reloadButton.setOnClickListener {
            binding.webView.reload()
        }
    }

    private fun syncUrlBar(url: String?) {
        currentFullUrl = url.orEmpty()
        if (!binding.urlBar.hasFocus()) binding.urlBar.setText(abbreviateUrl(currentFullUrl))
    }

    private fun abbreviateUrl(url: String): CharSequence {
        if (url.isEmpty()) return ""
        val uri = try { Uri.parse(url) } catch (_: Exception) { return url }
        val host = uri.host ?: return url

        val path = uri.encodedPath.orEmpty()
        val tail = buildString {
            if (path.isNotEmpty() && path != "/") append(path)
            uri.encodedQuery?.let { append('?').append(it) }
            uri.encodedFragment?.let { append('#').append(it) }
        }
        if (tail.isEmpty()) return host
        // At least one "/..." when there's a query/fragment but no path slashes.
        val ellipsisCount = maxOf(tail.count { it == '/' }, 1)

        val builder = SpannableStringBuilder(host).append("/...".repeat(ellipsisCount))
        val dim = MaterialColors.getColor(
            binding.urlBar,
            com.google.android.material.R.attr.colorOnSurfaceVariant
        )
        builder.setSpan(
            ForegroundColorSpan(dim),
            host.length, builder.length,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        return builder
    }

    private fun loadUrl(input: String) {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return
        val normalized = when {
            trimmed.startsWith("http://") || trimmed.startsWith("https://") -> trimmed
            trimmed.startsWith("about:") || trimmed.startsWith("file:") -> trimmed
            trimmed.contains('.') && !trimmed.contains(' ') -> "https://$trimmed"
            else -> "https://www.google.com/search?q=${Uri.encode(trimmed)}"
        }
        currentFullUrl = normalized
        binding.webView.loadUrl(normalized)
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
}
