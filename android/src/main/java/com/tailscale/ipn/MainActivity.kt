// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.RestrictionsManager
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration.SCREENLAYOUT_SIZE_LARGE
import android.content.res.Configuration.SCREENLAYOUT_SIZE_MASK
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.annotation.RequiresApi
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.core.net.toUri
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.tailscale.ipn.mdm.MDMSettings
import com.tailscale.ipn.mdm.ShowHide
import com.tailscale.ipn.ui.model.Ipn
import com.tailscale.ipn.ui.notifier.Notifier
import com.tailscale.ipn.ui.theme.AppTheme
import com.tailscale.ipn.ui.util.AndroidTVUtil
import com.tailscale.ipn.ui.util.set
import com.tailscale.ipn.ui.util.universalFit
import com.tailscale.ipn.ui.view.AboutView
import com.tailscale.ipn.ui.view.BugReportView
import com.tailscale.ipn.ui.view.DNSSettingsView
import com.tailscale.ipn.ui.view.ExitNodePicker
import com.tailscale.ipn.ui.view.HealthView
import com.tailscale.ipn.ui.view.IntroView
import com.tailscale.ipn.ui.view.LoginQRView
import com.tailscale.ipn.ui.view.LoginWithAuthKeyView
import com.tailscale.ipn.ui.view.LoginWithCustomControlURLView
import com.tailscale.ipn.ui.view.MDMSettingsDebugView
import com.tailscale.ipn.ui.view.MainView
import com.tailscale.ipn.ui.view.MainViewNavigation
import com.tailscale.ipn.ui.view.ManagedByView
import com.tailscale.ipn.ui.view.MullvadExitNodePicker
import com.tailscale.ipn.ui.view.MullvadExitNodePickerList
import com.tailscale.ipn.ui.view.MullvadInfoView
import com.tailscale.ipn.ui.view.NotificationsView
import com.tailscale.ipn.ui.view.PeerDetails
import com.tailscale.ipn.ui.view.PermissionsView
import com.tailscale.ipn.ui.view.RunExitNodeView
import com.tailscale.ipn.ui.view.SearchView
import com.tailscale.ipn.ui.view.SettingsView
import com.tailscale.ipn.ui.view.SplitTunnelAppPickerView
import com.tailscale.ipn.ui.view.SubnetRoutingView
import com.tailscale.ipn.ui.view.TaildropDirView
import com.tailscale.ipn.ui.view.TailnetLockSetupView
import com.tailscale.ipn.ui.view.UserSwitcherNav
import com.tailscale.ipn.ui.view.UserSwitcherView
import com.tailscale.ipn.ui.viewModel.ExitNodePickerNav
import com.tailscale.ipn.ui.viewModel.MainViewModel
import com.tailscale.ipn.ui.viewModel.MainViewModelFactory
import com.tailscale.ipn.ui.viewModel.PermissionsViewModel
import com.tailscale.ipn.ui.viewModel.PingViewModel
import com.tailscale.ipn.ui.viewModel.SettingsNav
import com.tailscale.ipn.ui.viewModel.VpnViewModel
import com.tailscale.ipn.util.TSLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import libtailscale.Libtailscale

class MainActivity : ComponentActivity() {
  private lateinit var navController: NavHostController
  private lateinit var vpnPermissionLauncher: ActivityResultLauncher<Intent>
  private val viewModel: MainViewModel by lazy {
    val app = App.get()
    vpnViewModel = app.getAppScopedViewModel()
    ViewModelProvider(this, MainViewModelFactory(vpnViewModel)).get(MainViewModel::class.java)
  }
  private lateinit var vpnViewModel: VpnViewModel
  val permissionsViewModel: PermissionsViewModel by viewModels()

  companion object {
    private const val TAG = "Main Activity"
    private const val START_AT_ROOT = "startAtRoot"
  }

  private fun Context.isLandscapeCapable(): Boolean {
    return (resources.configuration.screenLayout and SCREENLAYOUT_SIZE_MASK) >=
        SCREENLAYOUT_SIZE_LARGE
  }

  // The loginQRCode is used to track whether or not we should be rendering a QR code
  // to the user.  This is used only on TV platforms with no browser in lieu of
  // simply opening the URL.  This should be consumed once it has been handled.
  private val loginQRCode: StateFlow<String?> = MutableStateFlow(null)

  @RequiresApi(Build.VERSION_CODES.TIRAMISU)
  @SuppressLint("SourceLockedOrientationActivity")
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    // grab app to make sure it initializes
    App.get()
    vpnViewModel = ViewModelProvider(App.get()).get(VpnViewModel::class.java)

    val rm = getSystemService(Context.RESTRICTIONS_SERVICE) as RestrictionsManager
    MDMSettings.update(App.get(), rm)

    if (MDMSettings.onboardingFlow.flow.value.value == ShowHide.Hide ||
        MDMSettings.authKey.flow.value.value != null) {
      setIntroScreenViewed(true)
    }

    // (jonathan) TODO: Force the app to be portrait on small screens until we have
    // proper landscape layout support
    if (!isLandscapeCapable()) {
      requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
    }

    installSplashScreen()

    vpnPermissionLauncher =
        registerForActivityResult(VpnPermissionContract()) { granted ->
          if (granted) {
            TSLog.d("VpnPermission", "VPN permission granted")
            vpnViewModel.setVpnPrepared(true)
            App.get().startVPN()
          } else {
            if (isAnotherVpnActive(this)) {
              TSLog.d("VpnPermission", "Another VPN is likely active")
              showOtherVPNConflictDialog()
            } else {
              TSLog.d("VpnPermission", "Permission was denied by the user")
              vpnViewModel.setVpnPrepared(false)

              AlertDialog.Builder(this)
                  .setTitle(R.string.vpn_permission_needed)
                  .setMessage(R.string.vpn_explainer)
                  .setPositiveButton(R.string.try_again) { _, _ ->
                    viewModel.showVPNPermissionLauncherIfUnauthorized()
                  }
                  .setNegativeButton(R.string.cancel, null)
                  .show()
            }
          }
        }
    viewModel.setVpnPermissionLauncher(vpnPermissionLauncher)

    val directoryPickerLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
          if (uri != null) {
            try {
              // Try to take persistable permissions for both read and write.
              contentResolver.takePersistableUriPermission(
                  uri,
                  Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            } catch (e: SecurityException) {
              TSLog.e("MainActivity", "Failed to persist permissions: $e")
            }

            // Check if write permission is actually granted.
            val writePermission =
                this.checkUriPermission(
                    uri, Process.myPid(), Process.myUid(), Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            if (writePermission == PackageManager.PERMISSION_GRANTED) {
              TSLog.d("MainActivity", "Write permission granted for $uri")

              lifecycleScope.launch(Dispatchers.IO) {
                try {
                  Libtailscale.setDirectFileRoot(uri.toString())
                  TaildropDirectoryStore.saveFileDirectory(uri)
                  permissionsViewModel.refreshCurrentDir()
                } catch (e: Exception) {
                  TSLog.e("MainActivity", "Failed to set Taildrop root: $e")
                }
              }
            } else {
              TSLog.d(
                  "MainActivity",
                  "Write access not granted for $uri. Falling back to internal storage.")
              // Don't save directory URI and fall back to internal storage.
            }
          } else {
            TSLog.d(
                "MainActivity", "Taildrop directory not saved. Will fall back to internal storage.")

            // Fall back to internal storage.
          }
        }

    viewModel.setDirectoryPickerLauncher(directoryPickerLauncher)

    setContent {
      navController = rememberNavController()

      AppTheme {
        Surface(color = MaterialTheme.colorScheme.inverseSurface) { // Background for the letterbox
          Surface(modifier = Modifier.universalFit()) { // Letterbox for AndroidTV
            NavHost(
                navController = navController,
                startDestination = "main",
                enterTransition = {
                  slideInHorizontally(
                      animationSpec = tween(250, easing = LinearOutSlowInEasing),
                      initialOffsetX = { it }) +
                      fadeIn(animationSpec = tween(500, easing = LinearOutSlowInEasing))
                },
                exitTransition = {
                  slideOutHorizontally(
                      animationSpec = tween(250, easing = LinearOutSlowInEasing),
                      targetOffsetX = { -it }) +
                      fadeOut(animationSpec = tween(500, easing = LinearOutSlowInEasing))
                },
                popEnterTransition = {
                  slideInHorizontally(
                      animationSpec = tween(250, easing = LinearOutSlowInEasing),
                      initialOffsetX = { -it }) +
                      fadeIn(animationSpec = tween(500, easing = LinearOutSlowInEasing))
                },
                popExitTransition = {
                  slideOutHorizontally(
                      animationSpec = tween(250, easing = LinearOutSlowInEasing),
                      targetOffsetX = { it }) +
                      fadeOut(animationSpec = tween(500, easing = LinearOutSlowInEasing))
                }) {
                  fun backTo(route: String): () -> Unit = {
                    navController.popBackStack(route = route, inclusive = false)
                  }

                  val mainViewNav =
                      MainViewNavigation(
                          onNavigateToSettings = { navController.navigate("settings") },
                          onNavigateToPeerDetails = {
                            navController.navigate("peerDetails/${it.StableID}")
                          },
                          onNavigateToExitNodes = { navController.navigate("exitNodes") },
                          onNavigateToHealth = { navController.navigate("health") },
                          onNavigateToSearch = {
                            viewModel.enableSearchAutoFocus()
                            navController.navigate("search")
                          })

                  val settingsNav =
                      SettingsNav(
                          onNavigateToBugReport = { navController.navigate("bugReport") },
                          onNavigateToAbout = { navController.navigate("about") },
                          onNavigateToDNSSettings = { navController.navigate("dnsSettings") },
                          onNavigateToSplitTunneling = { navController.navigate("splitTunneling") },
                          onNavigateToTailnetLock = { navController.navigate("tailnetLock") },
                          onNavigateToSubnetRouting = { navController.navigate("subnetRouting") },
                          onNavigateToMDMSettings = { navController.navigate("mdmSettings") },
                          onNavigateToManagedBy = { navController.navigate("managedBy") },
                          onNavigateToUserSwitcher = { navController.navigate("userSwitcher") },
                          onNavigateToPermissions = { navController.navigate("permissions") },
                          onBackToSettings = backTo("settings"),
                          onNavigateBackHome = backTo("main"))

                  val exitNodePickerNav =
                      ExitNodePickerNav(
                          onNavigateBackHome = {
                            navController.popBackStack(route = "main", inclusive = false)
                          },
                          onNavigateBackToExitNodes = backTo("exitNodes"),
                          onNavigateToMullvad = { navController.navigate("mullvad") },
                          onNavigateToMullvadInfo = { navController.navigate("mullvad_info") },
                          onNavigateBackToMullvad = backTo("mullvad"),
                          onNavigateToMullvadCountry = { navController.navigate("mullvad/$it") },
                          onNavigateToRunAsExitNode = { navController.navigate("runExitNode") })

                  val userSwitcherNav =
                      UserSwitcherNav(
                          backToSettings = backTo("settings"),
                          onNavigateHome = backTo("main"),
                          onNavigateCustomControl = {
                            navController.navigate("loginWithCustomControl")
                          },
                          onNavigateToAuthKey = { navController.navigate("loginWithAuthKey") })

                  composable("main", enterTransition = { fadeIn(animationSpec = tween(150)) }) {
                    MainView(loginAtUrl = ::login, navigation = mainViewNav, viewModel = viewModel)
                  }
                  composable("search") {
                    val autoFocus = viewModel.autoFocusSearch
                    SearchView(
                        viewModel = viewModel,
                        navController = navController,
                        onNavigateBack = { navController.popBackStack() },
                        autoFocus = autoFocus)
                  }
                  composable("settings") { SettingsView(settingsNav) }
                  composable("exitNodes") { ExitNodePicker(exitNodePickerNav) }
                  composable("health") { HealthView(backTo("main")) }
                  composable("mullvad") { MullvadExitNodePickerList(exitNodePickerNav) }
                  composable("mullvad_info") { MullvadInfoView(exitNodePickerNav) }
                  composable(
                      "mullvad/{countryCode}",
                      arguments =
                          listOf(navArgument("countryCode") { type = NavType.StringType })) {
                        MullvadExitNodePicker(
                            it.arguments!!.getString("countryCode")!!, exitNodePickerNav)
                      }
                  composable("runExitNode") { RunExitNodeView(exitNodePickerNav) }
                  composable(
                      "peerDetails/{nodeId}",
                      arguments = listOf(navArgument("nodeId") { type = NavType.StringType })) {
                        PeerDetails(
                            { navController.popBackStack() },
                            it.arguments?.getString("nodeId") ?: "",
                            PingViewModel())
                      }
                  composable("bugReport") { BugReportView(backTo("settings")) }
                  composable("dnsSettings") { DNSSettingsView(backTo("settings")) }
                  composable("splitTunneling") { SplitTunnelAppPickerView(backTo("settings")) }
                  composable("tailnetLock") { TailnetLockSetupView(backTo("settings")) }
                  composable("subnetRouting") { SubnetRoutingView(backTo("settings")) }
                  composable("about") { AboutView(backTo("settings")) }
                  composable("mdmSettings") { MDMSettingsDebugView(backTo("settings")) }
                  composable("managedBy") { ManagedByView(backTo("settings")) }
                  composable("userSwitcher") { UserSwitcherView(userSwitcherNav) }
                  composable("permissions") {
                    PermissionsView(
                        backTo("settings"),
                        { navController.navigate("taildropDir") },
                        { navController.navigate("notifications") })
                  }
                  composable("taildropDir") {
                    TaildropDirView(
                        backTo("permissions"), directoryPickerLauncher, permissionsViewModel)
                  }
                  composable("notifications") {
                    NotificationsView(backTo("permissions"), ::openApplicationSettings)
                  }
                  composable("intro", exitTransition = { fadeOut(animationSpec = tween(150)) }) {
                    IntroView(backTo("main"))
                  }
                  composable("loginWithAuthKey") {
                    LoginWithAuthKeyView(onNavigateHome = backTo("main"), backTo("userSwitcher"))
                  }
                  composable("loginWithCustomControl") {
                    LoginWithCustomControlURLView(
                        onNavigateHome = backTo("main"), backTo("userSwitcher"))
                  }
                }
            if (isIntroScreenViewedSet()) {
              navController.navigate("intro")
              setIntroScreenViewed(true)
            }
          }
        }

        // Login actions are app wide.  If we are told about a browse-to-url, we should render it
        // over whatever screen we happen to be on.
        loginQRCode.collectAsState().value?.let {
          LoginQRView(onDismiss = { loginQRCode.set(null) })
        }
      }
    }
  }

  init {
    // Watch the model's browseToURL and launch the browser when it changes or
    // pop up a QR code to scan
    lifecycleScope.launch {
      Notifier.browseToURL.collect { url ->
        url?.let {
          when (useQRCodeLogin()) {
            false -> Dispatchers.Main.run { login(it) }
            true -> loginQRCode.set(it)
          }
        }
      }
    }

    // Once we see a loginFinished event, clear the QR code which will dismiss the QR dialog.
    lifecycleScope.launch { Notifier.loginFinished.collect { _ -> loginQRCode.set(null) } }
  }

  private fun showOtherVPNConflictDialog() {
    AlertDialog.Builder(this)
        .setTitle(R.string.vpn_permission_denied)
        .setMessage(R.string.multiple_vpn_explainer)
        .setPositiveButton(R.string.go_to_settings) { _, _ ->
          // Intent to open the VPN settings
          val intent = Intent(Settings.ACTION_VPN_SETTINGS)
          startActivity(intent)
        }
        .setNegativeButton(R.string.cancel, null)
        .show()
  }

  fun isAnotherVpnActive(context: Context): Boolean {
    val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    val activeNetwork = connectivityManager.activeNetwork
    if (activeNetwork != null) {
      val networkCapabilities = connectivityManager.getNetworkCapabilities(activeNetwork)
      if (networkCapabilities != null &&
          networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
        return true
      }
    }
    return false
  }

  // Returns true if we should render a QR code instead of launching a browser
  // for login requests
  private fun useQRCodeLogin(): Boolean {
    return AndroidTVUtil.isAndroidTV()
  }

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    if (intent.getBooleanExtra(START_AT_ROOT, false)) {
      if (this::navController.isInitialized) {
        val previousEntry = navController.previousBackStackEntry
        TSLog.d("MainActivity", "onNewIntent: previousBackStackEntry = $previousEntry")
        if (this::navController.isInitialized) {
          val previousEntry = navController.previousBackStackEntry
          TSLog.d("MainActivity", "onNewIntent: previousBackStackEntry = $previousEntry")

          if (previousEntry != null) {
            navController.popBackStack(route = "main", inclusive = false)
          } else {
            TSLog.e(
                "MainActivity",
                "onNewIntent: No previous back stack entry, navigating directly to 'main'")
            navController.navigate("main") { popUpTo("main") { inclusive = true } }
          }
        }
      }
    }
  }

  private fun login(urlString: String) {
    // Launch coroutine to listen for state changes. When the user completes login, relaunch
    // MainActivity to bring the app back to focus.
    App.get().applicationScope.launch {
      try {
        Notifier.state.collect { state ->
          if (state > Ipn.State.NeedsMachineAuth) {
            val intent =
                Intent(applicationContext, MainActivity::class.java).apply {
                  addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                  action = Intent.ACTION_MAIN
                  addCategory(Intent.CATEGORY_LAUNCHER)
                  putExtra(START_AT_ROOT, true)
                }
            startActivity(intent)

            // Cancel coroutine once we've logged in
            this@launch.cancel()
          }
        }
      } catch (e: Exception) {
        TSLog.e(TAG, "Login: failed to start MainActivity: $e")
      }
    }

    val url = urlString.toUri()
    try {
      val customTabsIntent = CustomTabsIntent.Builder().build()
      customTabsIntent.launchUrl(this, url)
    } catch (e: Exception) {
      // Fallback to a regular browser if CustomTabsIntent fails
      try {
        val fallbackIntent = Intent(Intent.ACTION_VIEW, url)
        startActivity(fallbackIntent)
      } catch (e: Exception) {
        TSLog.e(TAG, "Login: failed to open browser: $e")
      }
    }
  }

  override fun onResume() {
    super.onResume()
    val restrictionsManager =
        this.getSystemService(Context.RESTRICTIONS_SERVICE) as RestrictionsManager
    lifecycleScope.launch(Dispatchers.IO) { MDMSettings.update(App.get(), restrictionsManager) }
  }

  override fun onStop() {
    super.onStop()
    val restrictionsManager =
        this.getSystemService(Context.RESTRICTIONS_SERVICE) as RestrictionsManager
    lifecycleScope.launch(Dispatchers.IO) { MDMSettings.update(App.get(), restrictionsManager) }
  }

  private fun openApplicationSettings() {
    val intent =
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
          putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
        }
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    startActivity(intent)
  }

  private fun isIntroScreenViewedSet(): Boolean {
    return !getSharedPreferences("introScreen", Context.MODE_PRIVATE).getBoolean("seen", false)
  }

  private fun setIntroScreenViewed(seen: Boolean) {
    getSharedPreferences("introScreen", Context.MODE_PRIVATE)
        .edit()
        .putBoolean("seen", seen)
        .apply()
  }
}

class VpnPermissionContract : ActivityResultContract<Intent, Boolean>() {
  override fun createIntent(context: Context, input: Intent): Intent {
    return input
  }

  override fun parseResult(resultCode: Int, intent: Intent?): Boolean {
    return resultCode == Activity.RESULT_OK
  }
}
