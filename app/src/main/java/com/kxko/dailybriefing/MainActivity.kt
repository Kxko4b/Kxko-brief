package com.kxko.dailybriefing

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.HapticFeedbackConstants
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.firebase.messaging.FirebaseMessaging

class MainActivity : ComponentActivity() {

    private var webView: WebView? = null
    private var swipeRefresh: SwipeRefreshLayout? = null
    private var fileChooserCallback: ValueCallback<Array<Uri>>? = null

    private var loadProgress by mutableFloatStateOf(0f)
    private var loadError by mutableStateOf(false)

    private val handler = Handler(Looper.getMainLooper())
    private var lastRefresh = 0L

    /** Quietly refreshes the dashboard every minute while the app is on screen. */
    private val autoRefresh = object : Runnable {
        override fun run() {
            refreshDashboard(force = false)
            handler.postDelayed(this, AUTO_REFRESH_MS)
        }
    }

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private val filePickerLauncher =
        registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            fileChooserCallback?.onReceiveValue(uris.toTypedArray())
            fileChooserCallback = null
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        // Use opaque, coordinated system bars. Transparent bars let page content appear
        // underneath Samsung's status/navigation areas and can make the UI feel unfinished.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(BG.toArgb()),
            navigationBarStyle = SystemBarStyle.dark(NAV_BAR.toArgb())
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
            window.navigationBarDividerColor = NAV_BAR.toArgb()
        }

        // Keep the existing Daily Briefing push-notification registration.
        FirebaseMessaging.getInstance()
            .subscribeToTopic("Kxko-Daily-Briefing")
            .addOnCompleteListener { task ->
                if (task.isSuccessful) println("Subscribed to Kxko-Daily-Briefing")
                else println("Topic subscription failed: ${task.exception}")
            }

        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
            if (task.isSuccessful) DeviceRegistration.register(task.result)
            else println("FCM TOKEN ERROR: ${task.exception}")
        }

        // Back: first step back inside the dashboard (sub-page -> menu -> overview), then web history, then close.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val view = webView
                if (view == null) {
                    finishFromBack()
                    return
                }
                view.evaluateJavascript("(window.kxkoBack ? window.kxkoBack() : false)") { result ->
                    if (result == "true") return@evaluateJavascript
                    if (view.canGoBack()) view.goBack() else finishFromBack()
                }
            }

            private fun finishFromBack() {
                isEnabled = false
                onBackPressedDispatcher.onBackPressed()
            }
        })

        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = BRASS,
                    background = BG,
                    surface = SURFACE,
                    onBackground = CREAM,
                    onSurface = CREAM
                )
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(BG)
                        .safeDrawingPadding()
                        .imePadding()
                ) {
                    AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = { createDashboard() }
                    )

                    if (loadProgress < 1f && !loadError) {
                        LinearProgressIndicator(
                            progress = { loadProgress },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(3.dp)
                                .align(Alignment.TopCenter),
                            color = BRASS,
                            trackColor = Color.Transparent
                        )
                    }

                    if (loadError) {
                        OfflineScreen(onRetry = {
                            loadError = false
                            webView?.loadUrl(ADMIN_URL)
                        })
                    }
                }
            }
        }
    }

    /** A pull-to-refresh container around the dashboard WebView. */
    private fun createDashboard(): SwipeRefreshLayout {
        val web = createAdminWebView()
        webView = web
        return SwipeRefreshLayout(this).also { layout ->
            swipeRefresh = layout
            layout.addView(web)
            layout.setColorSchemeColors(BRASS.toArgb())
            layout.setProgressBackgroundColorSchemeColor(SURFACE.toArgb())
            // Only start a pull when the page is scrolled to the very top.
            layout.setOnChildScrollUpCallback { _, _ -> web.scrollY > 0 }
            layout.setOnRefreshListener { refreshDashboard(force = true) }
        }
    }

    /**
     * Asks the dashboard page to reload what is on screen (window.kxkoRefresh).
     * If the page is an old cached version without that function, it falls back to a normal reload.
     */
    private fun refreshDashboard(force: Boolean) {
        val view = webView ?: return
        if (loadError) {
            swipeRefresh?.isRefreshing = false
            return
        }
        lastRefresh = System.currentTimeMillis()
        view.evaluateJavascript("typeof window.kxkoRefresh") { type ->
            if (type == "\"function\"") {
                view.evaluateJavascript("window.kxkoRefresh($force)", null)
                // Pull-to-refresh: let the spinner show for a moment so it feels like something happened.
                if (force) handler.postDelayed({ swipeRefresh?.isRefreshing = false }, 900)
            } else if (force) {
                view.reload()
                handler.postDelayed({ swipeRefresh?.isRefreshing = false }, 900)
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createAdminWebView(): WebView {
        return WebView(this).apply {
            setBackgroundColor(BG.toArgb())
            overScrollMode = android.view.View.OVER_SCROLL_NEVER

            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.databaseEnabled = true
            settings.loadsImagesAutomatically = true
            settings.javaScriptCanOpenWindowsAutomatically = false
            settings.setSupportMultipleWindows(false)
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            settings.allowFileAccess = false
            settings.allowContentAccess = true
            settings.cacheMode = WebSettings.LOAD_DEFAULT
            // Lets the dashboard know it is running inside the app (app-style tweaks on the web side).
            settings.userAgentString = settings.userAgentString + " KxkoApp/2"

            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)

            addJavascriptInterface(NativeBridge(this), "KxkoNative")

            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    val uri = request.url
                    if (uri.scheme == "https" && uri.host == ADMIN_HOST) return false

                    // Keep the dashboard in-app; send unrelated links to the browser.
                    return try {
                        startActivity(Intent(Intent.ACTION_VIEW, uri))
                        true
                    } catch (_: Exception) {
                        true
                    }
                }

                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                    loadError = false
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    swipeRefresh?.isRefreshing = false
                    lastRefresh = System.currentTimeMillis()
                }

                override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                    // Only a failed main page shows the offline screen (failed images etc. are ignored).
                    if (request.isForMainFrame) {
                        loadError = true
                        swipeRefresh?.isRefreshing = false
                    }
                }
            }

            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                    loadProgress = newProgress / 100f
                }

                override fun onShowFileChooser(
                    webView: WebView?,
                    filePathCallback: ValueCallback<Array<Uri>>?,
                    fileChooserParams: FileChooserParams?
                ): Boolean {
                    this@MainActivity.fileChooserCallback?.onReceiveValue(null)
                    this@MainActivity.fileChooserCallback = filePathCallback
                    return try {
                        filePickerLauncher.launch(arrayOf("*/*"))
                        true
                    } catch (_: Exception) {
                        this@MainActivity.fileChooserCallback = null
                        false
                    }
                }
            }

            loadUrl(ADMIN_URL)
        }
    }

    /** Small native helpers the web page can call. Only reachable from the dashboard's own pages. */
    private class NativeBridge(private val view: WebView) {
        @JavascriptInterface
        fun tick() {
            view.post { view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK) }
        }
    }

    override fun onResume() {
        super.onResume()
        MyFirebaseMessagingService.onPushWhileOpen = { refreshDashboard(force = false) }
        // Coming back after a while: refresh straight away, then keep going every minute.
        handler.removeCallbacks(autoRefresh)
        val stale = System.currentTimeMillis() - lastRefresh > STALE_AFTER_MS
        handler.postDelayed(autoRefresh, if (stale && lastRefresh != 0L) 0L else AUTO_REFRESH_MS)
    }

    override fun onPause() {
        handler.removeCallbacks(autoRefresh)
        MyFirebaseMessagingService.onPushWhileOpen = null
        super.onPause()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Tapped a notification while the app was already open.
        refreshDashboard(force = false)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        fileChooserCallback?.onReceiveValue(null)
        fileChooserCallback = null
        webView?.apply {
            stopLoading()
            loadUrl("about:blank")
            clearHistory()
            removeAllViews()
            destroy()
        }
        webView = null
        swipeRefresh = null
        super.onDestroy()
    }

    companion object {
        // GitHub Pages URL for the renamed diagrams repository.
        private const val ADMIN_URL = "https://kxko4b.github.io/diagrams/admin/"
        private const val ADMIN_HOST = "kxko4b.github.io"
        private const val AUTO_REFRESH_MS = 60_000L
        private const val STALE_AFTER_MS = 30_000L

        private val BG = Color(0xFF111315)
        private val SURFACE = Color(0xFF1A1D20)
        // A neutral grey gives Samsung's gesture/three-button navigation area a deliberate,
        // integrated finish instead of the transparent/black strip.
        private val NAV_BAR = Color(0xFF292C2F)
        private val BRASS = Color(0xFFC5A16B)
        private val CREAM = Color(0xFFF0EEE9)
    }
}

@Composable
private fun OfflineScreen(onRetry: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF101416))
            .pointerInput(Unit) { }
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("K", color = Color(0xFFC5A16B), fontSize = 56.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(12.dp))
        Text("Can't reach the dashboard", color = Color(0xFFEEE9DF), fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        Text(
            "Check your internet connection and try again.",
            color = Color(0xFFAAA99E),
            fontSize = 14.sp
        )
        Spacer(Modifier.height(24.dp))
        Button(
            onClick = onRetry,
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFC5A16B), contentColor = Color(0xFF201C15))
        ) {
            Text("Try again")
        }
    }
}
