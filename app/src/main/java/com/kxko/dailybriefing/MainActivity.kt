package com.kxko.dailybriefing

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.google.firebase.messaging.FirebaseMessaging

/**
 * Lightweight Android host for the V2 web app.
 * Android owns system integration; V2 owns the complete application interface.
 */
class MainActivity : ComponentActivity() {

    private var webView: WebView? = null
    private var fileChooserCallback: ValueCallback<Array<Uri>>? = null
    private var pendingGeoOrigin: String? = null
    private var pendingGeoCallback: GeolocationPermissions.Callback? = null
    private lateinit var updater: UpdateController

    private val filePickerLauncher =
        registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            fileChooserCallback?.onReceiveValue(uris.toTypedArray())
            fileChooserCallback = null
        }

    private val locationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            val granted = result[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                result[Manifest.permission.ACCESS_COARSE_LOCATION] == true
            pendingGeoCallback?.invoke(pendingGeoOrigin, granted, false)
            pendingGeoCallback = null
            pendingGeoOrigin = null
        }

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Start the always-armed cycling foreground service while the app is visible.
        // Mi Fitness can then trigger a ride without the user opening the Cycling screen.
        armCyclingDetectionIfReady()

        // Keep Android's status/navigation/gesture areas visible and reserve their insets.
        // This prevents the web app from drawing interactive controls underneath system UI.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = android.graphics.Color.rgb(11, 13, 12)
        window.navigationBarColor = android.graphics.Color.rgb(11, 13, 12)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
        window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)

        FirebaseMessaging.getInstance().subscribeToTopic("Kxko-Daily-Briefing")
        FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
            if (task.isSuccessful) DeviceRegistration.register(task.result)
        }

        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        val browser = createV2WebView()
        webView = browser
        setContentView(browser)

        ViewCompat.setOnApplyWindowInsetsListener(browser) { view, insets ->
            val safe = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            view.setPadding(
                safe.left,
                safe.top,
                safe.right,
                maxOf(safe.bottom, ime.bottom)
            )
            insets
        }
        ViewCompat.requestApplyInsets(browser)

        updater = UpdateController(this).apply {
            onUpdateAvailable = { update ->
                if (!isFinishing && !isDestroyed) {
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("Kxko update available")
                        .setMessage("Version ${update.versionName} is ready. Download it now?")
                        .setPositiveButton("Download & install") { _, _ -> startDownload() }
                        .setNegativeButton("Later") { _, _ -> dismiss() }
                        .setOnCancelListener { dismiss() }
                        .show()
                }
            }
            register()
            check(force = false)
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val page = webView
                if (page?.canGoBack() == true) {
                    page.goBack()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createV2WebView(): WebView {
        return WebView(this).apply {
            setBackgroundColor(android.graphics.Color.rgb(11, 13, 12))
            overScrollMode = View.OVER_SCROLL_NEVER
            isVerticalScrollBarEnabled = false
            isHorizontalScrollBarEnabled = false
            isScrollbarFadingEnabled = true

            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                loadsImagesAutomatically = true
                javaScriptCanOpenWindowsAutomatically = false
                setSupportMultipleWindows(false)
                setSupportZoom(false)
                builtInZoomControls = false
                displayZoomControls = false
                mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                allowFileAccess = false
                allowContentAccess = true
                cacheMode = WebSettings.LOAD_DEFAULT
                mediaPlaybackRequiresUserGesture = true
                userAgentString = userAgentString + " KxkoAndroid/V2"
            }

            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)

            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    val uri = request.url
                    if (uri.scheme == "https" && uri.host == V2_HOST) return false
                    return try {
                        startActivity(Intent(Intent.ACTION_VIEW, uri))
                        true
                    } catch (_: Exception) {
                        true
                    }
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    if (Uri.parse(url.orEmpty()).host == V2_HOST) {
                        MyFirebaseMessagingService.onPushWhileOpen = { webView?.reload() }
                    }
                }
            }

            webChromeClient = object : WebChromeClient() {
                override fun onShowFileChooser(
                    webView: WebView?,
                    filePathCallback: ValueCallback<Array<Uri>>?,
                    fileChooserParams: FileChooserParams?
                ): Boolean {
                    fileChooserCallback?.onReceiveValue(null)
                    fileChooserCallback = filePathCallback
                    return try {
                        filePickerLauncher.launch(arrayOf("*/*"))
                        true
                    } catch (_: Exception) {
                        fileChooserCallback = null
                        false
                    }
                }

                override fun onGeolocationPermissionsShowPrompt(
                    origin: String?,
                    callback: GeolocationPermissions.Callback?
                ) {
                    val trustedOrigin = origin?.startsWith("https://$V2_HOST") == true
                    if (!trustedOrigin || callback == null) {
                        callback?.invoke(origin, false, false)
                        return
                    }

                    val fineGranted = ContextCompat.checkSelfPermission(
                        this@MainActivity, Manifest.permission.ACCESS_FINE_LOCATION
                    ) == PackageManager.PERMISSION_GRANTED
                    val coarseGranted = ContextCompat.checkSelfPermission(
                        this@MainActivity, Manifest.permission.ACCESS_COARSE_LOCATION
                    ) == PackageManager.PERMISSION_GRANTED

                    if (fineGranted || coarseGranted) {
                        callback.invoke(origin, true, false)
                    } else {
                        pendingGeoOrigin = origin
                        pendingGeoCallback = callback
                        locationPermissionLauncher.launch(
                            arrayOf(
                                Manifest.permission.ACCESS_FINE_LOCATION,
                                Manifest.permission.ACCESS_COARSE_LOCATION
                            )
                        )
                    }
                }
            }

            loadUrl(V2_URL)
        }
    }

    override fun onResume() {
        super.onResume()
        MyFirebaseMessagingService.onPushWhileOpen = { webView?.reload() }
        webView?.onResume()
        armCyclingDetectionIfReady()
        if (::updater.isInitialized) {
            updater.resumePendingInstall()
            updater.check(force = false)
        }
    }

    private fun armCyclingDetectionIfReady() {
        val hasLocation = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!hasLocation || !WatchWorkoutNotificationListener.hasAccess(this)) return
        val intent = Intent(this, CyclingTrackerService::class.java).setAction(CyclingTrackerService.ACTION_ARM)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ContextCompat.startForegroundService(this, intent)
            else startService(intent)
        } catch (_: SecurityException) {
            // Android may deny location foreground-service startup if required permissions were revoked.
        }
    }

    override fun onPause() {
        MyFirebaseMessagingService.onPushWhileOpen = null
        webView?.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        if (::updater.isInitialized) updater.unregister()
        fileChooserCallback?.onReceiveValue(null)
        fileChooserCallback = null
        pendingGeoCallback?.invoke(pendingGeoOrigin, false, false)
        pendingGeoCallback = null
        webView?.apply {
            stopLoading()
            loadUrl("about:blank")
            clearHistory()
            removeAllViews()
            destroy()
        }
        webView = null
        super.onDestroy()
    }

    companion object {
        private const val V2_HOST = "kxko4b.github.io"
        private const val V2_URL = "https://kxko4b.github.io/V2/"
    }
}
