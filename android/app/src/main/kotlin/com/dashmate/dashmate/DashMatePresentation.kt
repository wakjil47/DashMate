@file:Suppress("DEPRECATION")

package com.dashmate.dashmate

import android.app.Presentation
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Display
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.dashmate.dashmate.deck.ParkingManager
import org.json.JSONObject
import java.net.URLEncoder

/** The Navigation dock button opens this page in the browser panel. */

class DashMatePresentation(
    private val hostContext: Context,
    display: Display,
) : Presentation(hostContext, display), DashMateStateBridge.StateListener {

    private val TAG = "DashMatePresentation"

    private lateinit var rootLayout: FrameLayout
    private var deckWebView: WebView? = null
    private var browserWebView: WebView? = null
    private lateinit var browserBar: LinearLayout
    private var dockBar: DashMateDockBar? = null
    private var carKeyboard: DashMateCarKeyboard? = null
    private var authOverlay: FrameLayout? = null
    private var authWebView: WebView? = null

    private var currentPage = "home"
    private var isBrowsingWeb = false
    private var isFullscreen = false
    private var currentZoom = 1.0f
    private var lastVisibleArea: Rect? = null

    /** Browser-bar labels, recoloured on a day/night switch ("⌂ Deck" uses the accent). */
    private val browserBarLabels = mutableListOf<TextView>()
    private var browserDeckLabel: TextView? = null
    private var authCard: FrameLayout? = null
    private var authCloseBtn: TextView? = null

    /** Android Auto flipped day/night: recolour the page and dock in place (no reload). */
    private val themeListener: (Boolean) -> Unit = { dark -> applyTheme(dark) }

    var onOpenKeyboard: ((String, String, (String) -> Unit) -> Unit)? = null

    /** The web field the DashMate keyboard is typing into: which WebView, and the hook's field id. */
    private var kbdTarget: WebView? = null
    private var kbdFieldId: String = ""

    /** dd_keyboard_hook.js, read once from assets. */
    private val keyboardHook: String by lazy {
        try {
            hostContext.assets.open("dd_keyboard_hook.js").bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            Log.e(TAG, "Could not read the keyboard hook", e)
            ""
        }
    }

    /** Set by DashMateCarScreen: closes DashMate and returns to the Android Auto screen. */
    var onExit: (() -> Unit)? = null

    // RECONSTRUCTED: jadx failed on onCreate; rebuilt from `--show-bad-code` output (logic is complete,
    // only the try/catch region around the saved-layout lookup had to be re-scoped).
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DashMateStateBridge.playWelcomeChime(context)

        try {
            val spBrowser = hostContext.getSharedPreferences("dd_car_browser", Context.MODE_PRIVATE)
            val spPrefs = hostContext.getSharedPreferences("DashMatePrefs", Context.MODE_PRIVATE)
            val layoutStr = spBrowser.getString("dashmate_layout", null)
                ?: spBrowser.getString("car_layouts_current", null)
                ?: spPrefs.getString("dashmate_layout", null)
            if (!layoutStr.isNullOrBlank()) {
                DashMateStateBridge.cachedLayoutJson = layoutStr
            }
        } catch (_: Exception) {
        }

        val cardIntent = Intent(context, DashMateMediaCardService::class.java)
        context.startForegroundService(cardIntent)

        rootLayout = FrameLayout(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            setBackgroundColor(DashMateTheme.bg())
        }

        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)

        // ---- Deck WebView (local drivedeck_*.html pages) ----
        deckWebView = WebView(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            )
            setBackgroundColor(Color.TRANSPARENT)
            cookieManager.setAcceptThirdPartyCookies(this, true)
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                allowFileAccess = true
                allowContentAccess = true
                allowFileAccessFromFileURLs = true
                allowUniversalAccessFromFileURLs = true
                mediaPlaybackRequiresUserGesture = false
                cacheMode = WebSettings.LOAD_DEFAULT
                useWideViewPort = true
                loadWithOverviewMode = true
            }
            webChromeClient = object : WebChromeClient() {}
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    applyDockStripFlag()
                    Log.d(TAG, "Car Deck page finished loading: $url")
                    injectKeyboardHook(view)
                    onStateUpdated(DashMateStateBridge.buildStateJson(hostContext))
                }
            }
            val jsInterface = AutoDeckJsInterface { deckWebView }
            addJavascriptInterface(jsInterface, "AutoDeck")
            addJavascriptInterface(jsInterface, "DashMateBridge")
            addJavascriptInterface(RadioJsInterface(hostContext), "DashMateRadio")
            addJavascriptInterface(jsInterface, "AutoDeckWeb")
            loadUrl("file:///android_asset/drivedeck_home.html")
        }
        rootLayout.addView(deckWebView)

        // Browser WebView is created on first use (ensureBrowser) to save memory.


        browserBar = createBrowserControls()
        browserBar.visibility = View.GONE
        rootLayout.addView(browserBar)

        // ---- Dock bar ----
        val density = context.resources.displayMetrics.density
        dockBar = DashMateDockBar(
            context,
            onPageSelected = { targetPage -> DashMateStateBridge.setPage(targetPage) },
            onExit = { onExit?.invoke() },
            onBack = { browserBack() },
        ).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                leftMargin = (16 * density + 0.5f).toInt()
                topMargin = (8 * density + 0.5f).toInt()
            }
        }
        rootLayout.addView(dockBar)

        // ---- On-screen keyboard ----
        carKeyboard = DashMateCarKeyboard(
            context,
            onSubmit = { text, isAddressBar -> injectBrowserText(text, isAddressBar) },
            onDismiss = { blurKeyboardField() },
            onChange = { text -> writeKeyboardField("set", text) },
        )
        rootLayout.addView(carKeyboard)

        setContentView(rootLayout)
        // Android Auto has no system keyboard on the car screen, and the phone's own IME must not pop
        // up for a focused web field either: the DashMate keyboard is the only one.
        window?.setFlags(
            WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM,
            WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM,
        )
        window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
        // State / theme listeners are added in onStart and removed in onStop (a matched pair).
        updateVisibleArea(lastVisibleArea)
    }

    override fun onStart() {
        super.onStart()
        DashMateStateBridge.removeListener(this) // never twice
        DashMateStateBridge.addListener(this)
        DashMateTheme.addListener(themeListener)
        // The theme may have changed while we were stopped (or while building the views).
        applyTheme(DashMateTheme.isDark)
    }

    private fun applyTheme(dark: Boolean) {
        if (!::rootLayout.isInitialized) return
        val mode = if (dark) "dark" else "light"
        deckWebView?.evaluateJavascript("window.ddTheme&&ddTheme('$mode')", null)
        dockBar?.applyTheme(dark)
        rootLayout.setBackgroundColor(DashMateTheme.bg(dark))
        browserWebView?.setBackgroundColor(DashMateTheme.bg(dark))
        carKeyboard?.applyTheme(dark)
        styleBrowserBar(dark)
        styleAuthOverlay(dark)
    }

    private fun styleBrowserBar(dark: Boolean) {
        if (!::browserBar.isInitialized) return
        val density = context.resources.displayMetrics.density
        browserBar.background = GradientDrawable().apply {
            // Surface at ~94% so the page shows faintly underneath, like the dock.
            setColor(Color.argb(240, Color.red(DashMateTheme.surface(dark)), Color.green(DashMateTheme.surface(dark)), Color.blue(DashMateTheme.surface(dark))))
            cornerRadius = 24 * density
            setStroke((1 * density).toInt(), DashMateTheme.border(dark))
        }
        for (tv in browserBarLabels) tv.setTextColor(DashMateTheme.ink(dark))
        browserDeckLabel?.setTextColor(DashMateTheme.accent(dark))
    }

    private fun styleAuthOverlay(dark: Boolean) {
        val overlay = authOverlay ?: return
        val density = context.resources.displayMetrics.density
        overlay.setBackgroundColor(if (dark) Color.parseColor("#CC0F1115") else Color.parseColor("#990F172A"))
        authCard?.background = GradientDrawable().apply {
            setColor(DashMateTheme.surface(dark))
            cornerRadius = 20 * density
            setStroke((1 * density).toInt(), DashMateTheme.border(dark))
        }
        authCloseBtn?.setTextColor(DashMateTheme.ink(dark))
    }

    fun toggleFullscreen(fullscreen: Boolean) {
        isFullscreen = fullscreen
        val prefs = hostContext.getSharedPreferences("dd_car_browser", Context.MODE_PRIVATE)
        prefs.edit().putBoolean("fullscreen", fullscreen).apply()
        updateVisibleArea(lastVisibleArea)
    }

    fun onSizeChanged(width: Int, height: Int) {
        rootLayout.post { updateVisibleArea(lastVisibleArea) }
    }

    fun onVisibleAreaChanged(visibleArea: Rect) {
        updateVisibleArea(visibleArea)
    }

    private fun isGoogleAuthUrl(url: String): Boolean =
        url.contains("accounts.google.com") || url.contains("/gsi/transform")

    private fun showAuthOverlay(url: String) {
        if (authOverlay == null) {
            val density = context.resources.displayMetrics.density
            val overlay = FrameLayout(context).apply {
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT,
                )
                setBackgroundColor(Color.parseColor("#CC070B14"))
            }
            val card = FrameLayout(overlay.context).apply {
                layoutParams = FrameLayout.LayoutParams((560 * density).toInt(), (420 * density).toInt()).apply {
                    gravity = Gravity.CENTER
                }
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#16253B"))
                    cornerRadius = 20 * density
                    setStroke((1 * density).toInt(), Color.parseColor("#335EF2D6"))
                }
            }
            authWebView = WebView(card.context).apply {
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT,
                ).apply {
                    setMargins((8 * density).toInt(), (40 * density).toInt(), (8 * density).toInt(), (8 * density).toInt())
                }
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                webViewClient = object : WebViewClient() {
                    override fun onPageStarted(v0: WebView?, u0: String?, f0: android.graphics.Bitmap?) {
                        super.onPageStarted(v0, u0, f0)
                        if (v0 != null && kbdTarget === v0) dropKeyboardTarget() // its field is gone with the old page
                    }
                    override fun onPageFinished(v: WebView?, u: String?) {
                        if (u != null && !u.contains("accounts.google.com")) {
                            closeAuthOverlay()
                            browserWebView?.reload()
                        } else {
                            injectKeyboardHook(v) // email / password boxes need the car keyboard too
                        }
                    }
                }
                addJavascriptInterface(AutoDeckJsInterface { authWebView }, "AutoDeck")
            }
            card.addView(authWebView)

            val closeBtn = TextView(card.context).apply {
                text = "×"
                textSize = 26f
                setTextColor(Color.WHITE)
                setPadding((16 * density).toInt(), (4 * density).toInt(), (16 * density).toInt(), (4 * density).toInt())
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                ).apply {
                    gravity = Gravity.TOP or Gravity.END
                }
                setOnClickListener { closeAuthOverlay() }
            }
            card.addView(closeBtn)

            overlay.addView(card)
            authOverlay = overlay
            authCard = card
            authCloseBtn = closeBtn
            styleAuthOverlay(DashMateTheme.isDark)
            rootLayout.addView(authOverlay)
        }
        authOverlay?.visibility = View.VISIBLE
        authWebView?.loadUrl(url)
    }

    private fun closeAuthOverlay() {
        if (kbdTarget != null && kbdTarget === authWebView) {
            carKeyboard?.hide()
            blurKeyboardField()
        }
        authOverlay?.visibility = View.GONE
        authWebView?.loadUrl("about:blank")
    }

    private fun injectBrowserEnhancements(view: WebView?) {
        view?.evaluateJavascript(
            "(function () {\n  var d = document.documentElement;\n  function st(id, text) {\n    if (document.getElementById(id)) return;\n    var s = document.createElement('style');\n    s.id = id;\n    s.textContent = text;\n    d.appendChild(s);\n  }\n  st('dd-corner', 'ytd-masthead #container.ytd-masthead{padding-right:88px!important}');\n  st('dd-tap', 'html,body{touch-action:manipulation!important}');\n  st('dd-fit', 'ytd-miniplayer{transform:scale(.62)!important;transform-origin:bottom right!important}');\n  st('dd-fs', 'video{object-fit:cover!important}');\n\n})();",
            null,
        )
    }

    override fun onSwitchPage(page: String) {
        // A link sent from the phone ("Show on car" in Browse) opens even if the browser is already up.
        val pendingUrl = if (page == "web" || page == "browse") DashMateStateBridge.takePendingWebUrl() else null
        if (currentPage == page && pendingUrl == null) {
            val isWebPage = page == "web" || page == "browse"
            if (if (isWebPage) isBrowsingWeb else !isBrowsingWeb) return
        }
        currentPage = page

        if (page == "web" || page == "browse") {
            loadWebUrl(pendingUrl ?: "https://m.youtube.com")
            return
        }

        // Navigation: DashMate's own page (place search + Waze live traffic map; a result starts
        // Waze navigation). Waze's website search is refused inside apps (reCAPTCHA, HTTP 403).

        exitBrowserToHome(false)
        // exitBrowserToHome marks the page as "home"; record the page really shown, or the Home
        // button (and any card) would think it is already there and do nothing.
        currentPage = page
        DashMateStateBridge.currentPage = page
        if (kbdTarget === deckWebView) dropKeyboardTarget() // the field goes away with the page
        val assetUrl = when (page) {
            "lyrics" -> "file:///android_asset/drivedeck_lyrics.html"
            "prayer" -> "file:///android_asset/drivedeck_prayer.html"
            "radio" -> "file:///android_asset/dashmate_radio.html"
            "nav" -> "file:///android_asset/drivedeck_nav.html"
            else -> "file:///android_asset/drivedeck_home.html"
        }
        deckWebView?.loadUrl(assetUrl)
        dockBar?.updateCurrentPage(page)
    }

    override fun onExecuteScript(script: String) {
        deckWebView?.post { deckWebView?.evaluateJavascript(script, null) }
    }

    /** Creates the browser WebView the first time it is needed. */
    private fun ensureBrowser(): WebView {
        browserWebView?.let { return it }
        val wv = WebView(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            )
            setBackgroundColor(DashMateTheme.bg())
            visibility = View.GONE
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                allowFileAccess = true
                allowContentAccess = true
                mediaPlaybackRequiresUserGesture = false
                cacheMode = WebSettings.LOAD_DEFAULT
                useWideViewPort = true
                loadWithOverviewMode = true
                userAgentString = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:140.0) Gecko/20100101 Firefox/140.0"
                setSupportZoom(true)
                builtInZoomControls = true
                displayZoomControls = false
            }
            settings.setGeolocationEnabled(true)
            webChromeClient = object : WebChromeClient() {
                // Let Waze's Live Map centre on the car (the app already holds location permission).
                // Only waze.com gets it; other sites in the browser are refused.
                override fun onGeolocationPermissionsShowPrompt(
                    origin: String?,
                    callback: GeolocationPermissions.Callback?,
                ) {
                    val host = origin?.let { Uri.parse(it).host } ?: ""
                    val allow = host == "waze.com" || host.endsWith(".waze.com")
                    callback?.invoke(origin, allow, false)
                }
            }
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                    val url = request?.url?.toString() ?: ""
                    if (isGoogleAuthUrl(url)) {
                        showAuthOverlay(url)
                        return true
                    }
                    return false
                }

                // Slow sites (YouTube) show fields long before they finish: hook as soon as they paint.
                override fun onPageStarted(v0: WebView?, u0: String?, f0: android.graphics.Bitmap?) {
                    super.onPageStarted(v0, u0, f0)
                    if (v0 != null && kbdTarget === v0) dropKeyboardTarget() // its field is gone with the old page
                }
                override fun onPageCommitVisible(view: WebView?, url: String?) {
                    super.onPageCommitVisible(view, url)
                    injectKeyboardHook(view)
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    Log.d(TAG, "Browser page finished loading: $url")
                    injectBrowserEnhancements(view)
                    injectKeyboardHook(view)
                }
            }
            val jsInterface = AutoDeckJsInterface { browserWebView }
            addJavascriptInterface(jsInterface, "AutoDeck")
            addJavascriptInterface(jsInterface, "AutoDeckWeb")
        }
        browserWebView = wv
        // Sit just above the deck, below the browser bar / dock / keyboard.
        rootLayout.addView(wv, rootLayout.indexOfChild(deckWebView) + 1)
        updateVisibleArea(lastVisibleArea)
        return wv
    }

    /** Frees the browser (and whatever page it held, e.g. YouTube) when leaving it. */
    private fun releaseBrowser() {
        val wv = browserWebView ?: return
        if (kbdTarget === wv) dropKeyboardTarget()
        browserWebView = null
        try {
            wv.stopLoading()
            wv.loadUrl("about:blank")
            rootLayout.removeView(wv)
            wv.removeAllViews()
            wv.destroy()
        } catch (_: Exception) {
        }
    }

    /** Shows [url] in the browser panel; [highlight] is the dock button to light up ("web" or "nav"). */
    fun loadWebUrl(url: String, highlight: String = "web") {
        ensureBrowser()
        isBrowsingWeb = true
        dockBar?.visibility = View.VISIBLE
        dockBar?.updateCurrentPage(highlight)
        deckWebView?.visibility = View.GONE
        browserWebView?.visibility = View.VISIBLE
        // No floating bar over websites any more (it covered YouTube's own buttons): Home and ‹ Back are in the dock.
        browserBar.visibility = View.GONE
        dockBar?.setBackVisible(true)
        currentZoom = 1.0f
        browserWebView?.loadUrl(url)
    }

    /** Dock ‹ Back: the website's previous page, or back to the deck when there is none. */
    private fun browserBack() {
        val wv = browserWebView
        if (isBrowsingWeb && wv != null && wv.canGoBack()) wv.goBack() else exitBrowserToHome()
    }

    fun exitBrowserToHome(reloadDeck: Boolean = false) {
        if (!isBrowsingWeb && currentPage == "home") return

        isBrowsingWeb = false
        currentPage = "home"
        // Keep the shared page in step, or tapping the same browser/nav button again would do nothing.
        DashMateStateBridge.currentPage = "home"
        releaseBrowser()
        browserBar.visibility = View.GONE
        dockBar?.setBackVisible(false)
        closeAuthOverlay()
        dockBar?.visibility = View.VISIBLE
        dockBar?.updateCurrentPage("home")
        deckWebView?.visibility = View.VISIBLE
        if (reloadDeck) {
            deckWebView?.loadUrl("file:///android_asset/drivedeck_home.html")
        } else {
            onStateUpdated(DashMateStateBridge.buildStateJson(hostContext))
        }
    }

    private fun createBrowserControls(): LinearLayout {
        val density = context.resources.displayMetrics.density
        val bar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((12 * density).toInt(), (4 * density).toInt(), (12 * density).toInt(), (4 * density).toInt())
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#D90B0F1A"))
                cornerRadius = 24 * density
                setStroke((1 * density).toInt(), Color.parseColor("#33FFFFFF"))
            }
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                (42 * density).toInt(),
            ).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                topMargin = (10 * density).toInt()
            }
        }

        bar.addView(TextView(context).apply {
            text = "⌂ Deck"
            browserDeckLabel = this
            textSize = 14f
            setPadding(14, 6, 14, 6)
            setOnClickListener { exitBrowserToHome() }
        })

        bar.addView(TextView(context).apply {
            text = "◀"
            browserBarLabels.add(this)
            textSize = 16f
            setPadding(12, 6, 12, 6)
            setOnClickListener {
                if (browserWebView?.canGoBack() == true) {
                    browserWebView?.goBack()
                } else {
                    exitBrowserToHome()
                }
            }
        })

        bar.addView(TextView(context).apply {
            text = "▶"
            browserBarLabels.add(this)
            textSize = 16f
            setPadding(12, 6, 12, 6)
            setOnClickListener {
                if (browserWebView?.canGoForward() == true) {
                    browserWebView?.goForward()
                }
            }
        })

        bar.addView(TextView(context).apply {
            text = "↻"
            browserBarLabels.add(this)
            textSize = 16f
            setPadding(12, 6, 12, 6)
            setOnClickListener { browserWebView?.reload() }
        })

        bar.addView(TextView(context).apply {
            text = "🔍 Search"
            browserBarLabels.add(this)
            textSize = 14f
            setPadding(14, 6, 14, 6)
            setOnClickListener {
                val currentUrl = browserWebView?.url ?: ""
                showVirtualKeyboard(
                    if (currentUrl.startsWith("http")) currentUrl else "",
                    "Search YouTube, Google or enter URL...",
                    true,
                )
            }
        })

        browserBar = bar
        styleBrowserBar(DashMateTheme.isDark)
        return bar
    }

    fun showVirtualKeyboard(initialText: String, hint: String, isAddressBar: Boolean) {
        rootLayout.post {
            if (isAddressBar) {
                // The address bar types into nothing on the page: let go of any web field first.
                blurKeyboardField()
            }
            kbdShowSeq++
            carKeyboard?.show(initialText, hint, isAddressBar)
        }
    }

    /** Puts the text-field hook into a page (deck or browser); the hook itself is idempotent. */
    private fun injectKeyboardHook(view: WebView?) {
        val js = keyboardHook
        if (view == null || js.isEmpty()) return
        view.evaluateJavascript(js, null)
    }

    /** A web field asked for the keyboard (tap or focus, from dd_keyboard_hook.js). */
    private fun openKeyboardForField(source: WebView?, fieldId: String, value: String, hint: String, type: String) {
        rootLayout.post {
            val wv = source ?: return@post
            if (!wv.isShown) return@post // page hidden behind the browser / deck, or a closed sign-in card
            // Switching to another field: release the previous one.
            if (carKeyboard?.isShowing() == true && (kbdTarget !== wv || kbdFieldId != fieldId)) blurKeyboardField()
            kbdTarget = wv
            kbdFieldId = fieldId
            kbdShowSeq++
            carKeyboard?.show(value, hint, false, type)
        }
    }

    /** Bumped each time the keyboard is shown, so a delayed close can tell a field was just opened. */
    private var kbdShowSeq = 0

    /** Runs __ddKbd.[op](id, text) in the WebView that owns the field. */
    private fun writeKeyboardField(op: String, text: String) {
        val wv = kbdTarget ?: browserWebView ?: return
        val script = "window.__ddKbd&&__ddKbd.$op(${JSONObject.quote(kbdFieldId)},${JSONObject.quote(text)})"
        wv.evaluateJavascript(script, null)
    }

    /** The field's page is going away: close the keyboard without writing anything. */
    private fun dropKeyboardTarget() {
        carKeyboard?.hide()
        kbdTarget = null
        kbdFieldId = ""
    }

    /** The keyboard closed without Go: blur the field so the page knows typing ended. */
    private fun blurKeyboardField() {
        val wv = kbdTarget ?: return
        wv.evaluateJavascript("window.__ddKbd&&__ddKbd.close(${JSONObject.quote(kbdFieldId)})", null)
        kbdTarget = null
        kbdFieldId = ""
    }

    fun injectBrowserText(text: String, isAddressBar: Boolean = false) {
        if (isAddressBar) {
            val query = text.trim()
            if (query.startsWith("http://") || query.startsWith("https://")) {
                loadWebUrl(query)
            } else if (query.contains(".") && !query.contains(" ")) {
                loadWebUrl("https://$query")
            } else {
                loadWebUrl("https://www.google.com/search?q=" + URLEncoder.encode(query, "UTF-8"))
            }
            return
        }

        val wv = kbdTarget ?: browserWebView ?: return
        val id = JSONObject.quote(kbdFieldId)
        val t = JSONObject.quote(text)
        kbdTarget = null
        kbdFieldId = ""
        // The hook is normally there already; inject it first in case the page was never finished.
        wv.evaluateJavascript(keyboardHook + "\n;window.__ddKbd&&__ddKbd.submit($id,$t)", null)
    }

    fun updateVisibleArea(visibleArea: Rect?) {
        lastVisibleArea = visibleArea
        if (visibleArea == null || !::rootLayout.isInitialized) return

        rootLayout.post {
            val screenWidth = rootLayout.width
            val screenHeight = rootLayout.height
            if (screenWidth <= 0 || screenHeight <= 0) return@post

            val insetLeft = visibleArea.left
            val insetTop = visibleArea.top
            val insetRight = screenWidth - visibleArea.right
            val insetBottom = screenHeight - visibleArea.bottom

            val padLeft = if (!isFullscreen && insetLeft > 0) insetLeft else 0
            val padTop = if (!isFullscreen && insetTop > 0) insetTop else 0
            val padRight = if (!isFullscreen && insetRight > 0) insetRight else 0
            val padBottom = if (!isFullscreen && insetBottom > 0) insetBottom else 0

            for (wv in listOf(deckWebView, browserWebView)) {
                val wlp = wv?.layoutParams as? FrameLayout.LayoutParams ?: continue
                if (wlp.leftMargin != padLeft || wlp.topMargin != padTop || wlp.rightMargin != padRight || wlp.bottomMargin != padBottom) {
                    wlp.setMargins(padLeft, padTop, padRight, padBottom)
                    wv.layoutParams = wlp
                }
            }

            val density = context.resources.displayMetrics.density
            val lp = dockBar?.layoutParams as? FrameLayout.LayoutParams
            if (lp != null) {
                // AA may keep a strip free at the top (its full-screen button). When the dock fits there,
                // centre it in that empty strip so it never covers the clock; otherwise 8 dp from the top
                // and the page keeps a strip free under it (drivedeck_home.html, html:not(.dock-in-strip)).
                val dockH = dockBar?.height?.takeIf { it > 0 } ?: (46 * density + 0.5f).toInt()
                dockInStrip = padTop >= dockH + (8 * density).toInt()
                lp.leftMargin = (16 * density + 0.5f).toInt() + padLeft
                lp.topMargin = if (dockInStrip) (padTop - dockH) / 2 else (8 * density + 0.5f).toInt() + padTop
                dockBar?.layoutParams = lp
                applyDockStripFlag()
            }
        }
    }

    /** True while the dock sits in AA's reserved top strip (outside the deck page). */
    private var dockInStrip = false

    private fun applyDockStripFlag() {
        deckWebView?.evaluateJavascript(
            "document.documentElement.classList.toggle('dock-in-strip', $dockInStrip)", null)
    }

    override fun onStop() {
        DashMateStateBridge.removeListener(this)
        DashMateTheme.removeListener(themeListener)
        super.onStop()
    }

    override fun dismiss() {
        com.dashmate.dashmate.deck.CarLocation.stop(hostContext)
        DashMateStateBridge.removeListener(this)
        DashMateTheme.removeListener(themeListener)
        releaseBrowser()
        try {
            deckWebView?.let { rootLayout.removeView(it); it.destroy() }
            deckWebView = null
        } catch (_: Exception) {
        }
        super.dismiss()
    }

    override fun onStateUpdated(stateJson: String) {
        if (isBrowsingWeb) return
        val quotedJson = JSONObject.quote(stateJson)
        val script = "if (window.ddUpdate) { window.ddUpdate($quotedJson); }"
        deckWebView?.post { deckWebView?.evaluateJavascript(script, null) }
    }

    /** A real finger touch from the phone's car-screen preview, already in this display's pixels. */
    fun dispatchPhoneTouch(ev: MotionEvent) {
        if (!::rootLayout.isInitialized) return
        rootLayout.dispatchTouchEvent(ev)
    }

    fun dispatchVirtualClick(x: Float, y: Float) {
        val now = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x, y, 0)
        val up = MotionEvent.obtain(now, now + 50, MotionEvent.ACTION_UP, x, y, 0)
        if (!::rootLayout.isInitialized) return

        // RECONSTRUCTED: jadx failed on this lambda; rebuilt from the bytecode dump.
        // A tap above an open keyboard reaches the page first (e.g. picking a destination from Waze's
        // suggestion list), then the keyboard closes. Closing first blurred the field, which hid the
        // list before the tap landed, so suggestions could never be picked.
        rootLayout.post {
            if (carKeyboard?.isShowing() == true && y < (carKeyboard?.top ?: 0).toFloat()) {
                val seq = kbdShowSeq
                rootLayout.dispatchTouchEvent(down)
                rootLayout.dispatchTouchEvent(up)
                rootLayout.postDelayed({
                    // Unless the tap opened a field (keyboard shown again): then it stays.
                    if (carKeyboard?.isShowing() == true && kbdShowSeq == seq) {
                        carKeyboard?.hide()
                        blurKeyboardField()
                    }
                }, 250L)
            } else {
                rootLayout.dispatchTouchEvent(down)
                rootLayout.dispatchTouchEvent(up)
            }
            down.recycle()
            up.recycle()
        }
    }

    fun dispatchVirtualScroll(startX: Float, startY: Float, distanceX: Float, distanceY: Float) {
        val now = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, startX, startY, 0)
        val move = MotionEvent.obtain(now, now + 20, MotionEvent.ACTION_MOVE, startX - distanceX, startY - distanceY, 0)
        val up = MotionEvent.obtain(now, now + 40, MotionEvent.ACTION_UP, startX - distanceX, startY - distanceY, 0)
        if (!::rootLayout.isInitialized) return

        rootLayout.post {
            if (isBrowsingWeb) {
                val script = """
                        (function(dx, dy) {
                          var target = document.scrollingElement || document.documentElement;
                          target.scrollBy(dx, dy);
                        })($distanceX, $distanceY);
                    """.trimIndent()
                browserWebView?.evaluateJavascript(script, null)
            }
            rootLayout.dispatchTouchEvent(down)
            rootLayout.dispatchTouchEvent(move)
            rootLayout.dispatchTouchEvent(up)
            down.recycle()
            move.recycle()
            up.recycle()
        }
    }

    /** [owner] is the WebView this bridge is attached to, so typing goes back to the right page. */
    inner class AutoDeckJsInterface(private val owner: () -> WebView?) {

        @JavascriptInterface
        fun media(action: String) {
            Log.d(TAG, "Media control clicked: $action")
            val am = hostContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            val keyCode = when (action) {
                "lyrics" -> {
                    onSwitchPage("lyrics")
                    return
                }
                "toggle" -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
                "next" -> KeyEvent.KEYCODE_MEDIA_NEXT
                "prev" -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
                else -> return
            }
            am?.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
            am?.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
        }

        @JavascriptInterface
        fun navigate(lat: Double, lng: Double) {
            DashMateStateBridge.launchNavigationCoords(hostContext, lat, lng)
        }

        @JavascriptInterface
        fun youtube(videoId: String) {
            Log.d(TAG, "YouTube requested: $videoId")
            DashMateStateBridge.currentYoutubeId = videoId
            loadWebUrl("https://m.youtube.com/watch?v=$videoId")
        }

        @JavascriptInterface
        fun site(url: String) {
            Log.d(TAG, "Open site requested: $url")
            if (url.startsWith("dashmate://masjid")) {
                CarNav.toNearest(hostContext, com.dashmate.dashmate.deck.NearbyFinder.Kind.MASJID)
                return
            }
            if (url.startsWith("dashmate://petrol")) {
                CarNav.toNearest(hostContext, com.dashmate.dashmate.deck.NearbyFinder.Kind.PETROL)
                return
            }
            if (url.startsWith("geo:")) {
                CarNav.toQuery(hostContext, Uri.parse(url).getQueryParameter("q") ?: url.removePrefix("geo:"))
                return
            }
            if (url.startsWith("dashmate://parking")) {
                val spot = ParkingManager.getSpot(hostContext)
                if (spot != null) {
                    ParkingManager.navigateBack(hostContext)
                }
                return
            }
            if (url.startsWith("http://") || url.startsWith("https://")) {
                loadWebUrl(url)
                return
            }
            try {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                hostContext.startActivity(intent)
            } catch (_: Exception) {
            }
        }

        @JavascriptInterface
        fun page(target: String) {
            // From a Home card. "custom:<mode>" opens the Custom page on that topic (weather,
            // sports, calendar, radar, ev). Goes through the shared state so the car and the
            // phone agree on the page; same page again just refreshes it.
            val page = target.substringBefore(':')
            val mode = target.substringAfter(':', "")
            Handler(Looper.getMainLooper()).post {
                if (page == "custom" && mode.isNotBlank()) DashMateStateBridge.customCardMode = mode
                if (DashMateStateBridge.currentPage == page && page != "custom") onSwitchPage(page)
                else DashMateStateBridge.setPage(page)
                if (page == "custom") DashMateStateBridge.notifyStateChanged(hostContext)
            }
        }

        @JavascriptInterface
        fun textField(initialValue: String, hint: String) {
            // Older entry point: types into the page's last focused field.
            Handler(Looper.getMainLooper()).post {
                openKeyboardForField(owner(), "", initialValue, hint, "text")
            }
        }

        /** From dd_keyboard_hook.js: field [id] in this page was tapped or focused. */
        @JavascriptInterface
        fun editText(id: String, value: String, hint: String, type: String) {
            Handler(Looper.getMainLooper()).post {
                openKeyboardForField(owner(), id, value, hint, type)
            }
        }

        @JavascriptInterface
        fun audibleVideo() {
        }

        /** "dark" or "light": read by each page's theme hook before first paint (no white flash). */
        @JavascriptInterface
        fun theme(): String = DashMateTheme.mode()

        /** Navigation app for routes: "waze" or "maps"; an empty [choice] only reads it. */
        @JavascriptInterface
        fun navApp(choice: String): String {
            if (choice.isNotBlank()) CarNav.setNavApp(hostContext, choice)
            return CarNav.navApp(hostContext)
        }

        /** Lyrics Earlier / Later on the car: changes the car audio delay; returns the new value (ms). */
        @JavascriptInterface
        fun lyricsDelay(deltaMs: Int): Int = DashMateStateBridge.changeLyricsDelayMs(hostContext, deltaMs)

        /** The car's position for the Home map card (JSON), or "" before the first fix. */
        @JavascriptInterface
        fun location(): String = com.dashmate.dashmate.deck.CarLocation.json(hostContext)

        /** "tall" or "auto": the phone's Car layout setting, read by dd_tall.js before first paint. */
        @JavascriptInterface
        fun layoutMode(): String = DashMateStateBridge.layoutMode(context)
    }
}
