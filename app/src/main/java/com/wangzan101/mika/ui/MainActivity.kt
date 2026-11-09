package com.wangzan101.mika.ui

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.wangzan101.mika.BuildConfig
import com.wangzan101.mika.MikaApp
import com.wangzan101.mika.about.MikaLinks
import com.wangzan101.mika.about.UpdateDelivery
import com.wangzan101.mika.cap.phone.setup.DeveloperSettings
import com.wangzan101.mika.cap.phone.setup.PairingNotifier
import com.wangzan101.mika.ui.components.KeyboardDismissArea
import com.wangzan101.mika.ui.theme.appearanceDark
import com.wangzan101.mika.settings.FeatureFlags
import com.wangzan101.mika.settings.SystemStatusMonitor
import com.wangzan101.mika.ui.theme.MikaTheme
import com.wangzan101.mika.ui.theme.animationsOff
import com.wangzan101.mika.ui.theme.systemBarsFor
import kotlinx.coroutines.launch

/** The public Flash phone entry point exposes only the call workspace, pairing and updates. */
class MainActivity : ComponentActivity() {
    private val app get() = application as MikaApp
    private lateinit var monitor: SystemStatusMonitor
    private var callFocusRequest by mutableIntStateOf(0)
    private var pendingRoute by mutableStateOf<Route?>(null)
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted && !shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)) {
            open(appNotificationSettings())
        }
        monitor.refresh()
    }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(com.wangzan101.mika.i18n.AppLanguage.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        monitor = SystemStatusMonitor(this, app.phone.shellControl, callsOnly = true)
        callFocusRequest = savedInstanceState?.getInt(FOCUS_KEY) ?: 0
        takeRoute(intent)
        setContent {
            val dark = appearanceDark()
            LaunchedEffect(dark) { systemBarsFor(dark) }
            MikaTheme(dark = dark, reduceMotion = animationsOff(this)) {
                KeyboardDismissArea(Modifier.fillMaxSize()) {
                    val nav = rememberNavStack(root = Route.Calls)
                    val states = rememberSaveableStateHolder()
                    val status by monitor.status.collectAsState()
                    val update by app.updates.state.collectAsState()
                    LaunchedEffect(pendingRoute) {
                        when (pendingRoute) {
                            Route.About -> nav.push(Route.About)
                            Route.Calls -> { nav.reset(); callFocusRequest++ }
                            else -> Unit
                        }
                        pendingRoute = null
                    }
                    BackHandler(enabled = nav.canGoBack) { nav.back() }
                    val back: () -> Unit = { nav.back() }
                    when (nav.current) {
                        Route.PhoneSetup -> PhoneSetupScreen(status, repair = false, onFix = ::perform,
                            callOnly = true, onClose = back)
                        Route.About -> AboutScreen(
                            BuildConfig.VERSION_NAME, BuildConfig.GIT_HASH, BuildConfig.BETA, update,
                            issueUrl = remember {
                                MikaLinks.issueUrl(MikaLinks.Device(Build.MANUFACTURER, Build.MODEL,
                                    Build.VERSION.RELEASE, Build.VERSION.SDK_INT, BuildConfig.VERSION_NAME,
                                    BuildConfig.GIT_HASH))
                            },
                            onCheck = app.updates::checkInBackground,
                            onOpenUrl = { openInBrowser(this, it) },
                            onOpenLicense = { nav.push(Route.License) },
                            onBack = back,
                            diagnostics = {
                                val calls = app.calls.state.value
                                "Flash phone ${BuildConfig.VERSION_NAME} (${BuildConfig.GIT_HASH})\nAndroid ${Build.VERSION.RELEASE}\nTelecom: ${calls.prepared}; helper: ${calls.shellConnected}; audio: ${calls.audioReady}; notifications: ${calls.notificationsAvailable}"
                            },
                        )
                        Route.License -> LicensesScreen(onBack = back)
                        else -> states.SaveableStateProvider(Route.Calls.name) {
                            CallSettingsScreen(app.calls, onBack = back, standalone = true,
                                onHelperSetup = { nav.push(Route.PhoneSetup) }, onAbout = { nav.push(Route.About) },
                                updateAvailable = update.hasUpdate, focusRequest = callFocusRequest)
                        }
                    }
                }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt(FOCUS_KEY, callFocusRequest)
        super.onSaveInstanceState(outState)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        takeRoute(intent)
    }

    private fun takeRoute(intent: Intent) {
        pendingRoute = when {
            intent.getBooleanExtra(EXTRA_OPEN_UPDATES, false) -> Route.About
            intent.getBooleanExtra(EXTRA_OPEN_CALLS, false) -> Route.Calls
            else -> pendingRoute
        }
        intent.removeExtra(EXTRA_OPEN_UPDATES)
        intent.removeExtra(EXTRA_OPEN_CALLS)
    }

    override fun onStart() {
        super.onStart()
        val flags = FeatureFlags.get(this)
        if (flags.quitRequested()) flags.setQuitRequested(false)
        monitor.start()
        UpdateDelivery.schedule(this)
        app.updates.autoCheckInBackground()
    }

    override fun onResume() {
        super.onResume()
        monitor.refresh()
    }

    override fun onStop() {
        monitor.stop()
        super.onStop()
    }

    private fun perform(fix: Fix) {
        val phone = app.phone
        when (fix) {
            Fix.AllowNotifications -> {
                if (Build.VERSION.SDK_INT >= 33 &&
                    checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                ) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                else open(appNotificationSettings())
            }
            Fix.OpenPairingChannel -> openFirst(listOf(Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                .putExtra(Settings.EXTRA_CHANNEL_ID, PairingNotifier.ensureChannel(this).id),
                appNotificationSettings()))
            Fix.OpenAppInfo -> open(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
            Fix.RetryPhoneHelper -> phone.pairing.scope.launch { phone.shellControl.retry() }
            Fix.OpenAboutPhone -> openFirst(DeveloperSettings.aboutPhone())
            Fix.OpenDeveloperOptions -> openFirst(DeveloperSettings.developerOptionsPage())
            Fix.OpenUsbDebugging -> openFirst(DeveloperSettings.usbDebuggingPage())
            Fix.OpenWirelessDebugging -> openFirst(DeveloperSettings.wirelessDebuggingPage())
            Fix.OpenWifi -> openFirst(DeveloperSettings.wifiPage())
            else -> Unit
        }
    }

    private fun appNotificationSettings() =
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName)

    private fun openFirst(intents: List<Intent>) { firstThatStarts(intents, ::open) }

    private fun open(intent: Intent): Boolean = try {
        startActivity(intent)
        true
    } catch (_: ActivityNotFoundException) {
        false
    } catch (_: SecurityException) {
        false
    }

    companion object {
        // Constants retained for source compatibility with components excluded from this APK's manifest.
        const val EXTRA_OPEN_CONVERSATION = "open_conversation"
        const val EXTRA_OPEN_TEACH = "open_teach"
        const val EXTRA_TEACH_GOAL = "teach_goal"
        const val EXTRA_OPEN_UPDATES = "open_updates"
        const val EXTRA_OPEN_CALLS = "open_calls"
        private const val FOCUS_KEY = "callFocusRequest"
    }
}

internal fun openInBrowser(context: Context, url: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE))
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(context, AboutCopy.NO_BROWSER, Toast.LENGTH_SHORT).show()
    }
}

internal fun <T> firstThatStarts(candidates: List<T>, start: (T) -> Boolean): Boolean = candidates.any(start)
